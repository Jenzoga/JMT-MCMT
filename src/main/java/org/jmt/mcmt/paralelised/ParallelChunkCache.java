package org.jmt.mcmt.paralelised;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.asmdest.ASMHookTerminator;
import org.jmt.mcmt.config.GeneralConfig;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Per-ServerChunkCache chunk memo, installed by coremod hooks into
 * ServerChunkCache.getChunk / getChunkNow instead of swapping the
 * ServerChunkCache instance in ServerLevel's constructor. Leaving that
 * constructor call site intact is what lets other mods' injections into
 * the same instruction (moonlight's fake chunk cache, for one) still resolve.
 */
public final class ParallelChunkCache {

	private ParallelChunkCache() {
	}

	private static final Logger log = LogManager.getLogger();

	private static final int CACHE_SIZE = 512;
	private static final long EVICT_INTERVAL = 1024;

	private static volatile Field stateField;

	public static ChunkAccess preGetChunk(Object held, ServerChunkCache scp, int x, int z, ChunkStatus status, boolean create) {
		if (GeneralConfig.disabled || GeneralConfig.disableChunkProvider) {
			return null;
		}
		State st = state(held, scp);
		if (st == null) {
			return null;
		}
		ChunkAccess cached = st.lookup(ChunkPos.asLong(x, z), status);
		if (cached != null) {
			return cached;
		}
		if (ASMHookTerminator.isThreadPooled("Main", Thread.currentThread())) {
			return CompletableFuture.supplyAsync(() -> scp.getChunk(x, z, status, create), st.bounceExecutor).join();
		}
		return null;
	}

	public static void postGetChunk(ChunkAccess result, Object held, ServerChunkCache scp, int x, int z, ChunkStatus status) {
		if (result == null || GeneralConfig.disabled || GeneralConfig.disableChunkProvider) {
			return;
		}
		State st = state(held, scp);
		if (st != null) {
			st.store(ChunkPos.asLong(x, z), status, result);
		}
	}

	public static LevelChunk preGetChunkNow(Object held, ServerChunkCache scp, int x, int z) {
		if (GeneralConfig.disabled || GeneralConfig.disableChunkProvider) {
			return null;
		}
		State st = state(held, scp);
		if (st == null) {
			return null;
		}
		return (LevelChunk) st.lookup(ChunkPos.asLong(x, z), ChunkStatus.FULL);
	}

	public static void postGetChunkNow(LevelChunk result, Object held, ServerChunkCache scp, int x, int z) {
		if (result == null || GeneralConfig.disabled || GeneralConfig.disableChunkProvider) {
			return;
		}
		State st = state(held, scp);
		if (st != null) {
			st.store(ChunkPos.asLong(x, z), ChunkStatus.FULL, result);
		}
	}

	/**
	 * The fast path hands us the field value directly from the injected bytecode;
	 * only a never-initialised instance pays for the reflective first write.
	 */
	@Nullable
	private static State state(Object held, ServerChunkCache scp) {
		if (held instanceof State) {
			return (State) held;
		}
		State created = new State(scp);
		return writeField(scp, created) ? created : null;
	}

	private static boolean writeField(ServerChunkCache scp, State st) {
		try {
			Field f = resolveField();
			if (f == null) {
				return false;
			}
			f.set(scp, st);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			disable(e);
			return false;
		}
	}

	@Nullable
	private static Field resolveField() {
		Field f = stateField;
		if (f == null) {
			try {
				f = ServerChunkCache.class.getField("jmt$chunkCacheState");
				f.setAccessible(true);
			} catch (ReflectiveOperationException e) {
				disable(e);
				return null;
			}
			stateField = f;
		}
		return f;
	}

	private static void disable(Throwable cause) {
		GeneralConfig.disableChunkProvider = true;
		log.warn("MCMT parallel chunk cache disabled: injected state field is not reachable", cause);
	}

	static final class State {
		private final Map<ChunkCacheAddress, ChunkCacheLine> chunkCache = new ConcurrentHashMap<>();
		private final AtomicInteger access = new AtomicInteger(Integer.MIN_VALUE);
		private final AtomicLong stores = new AtomicLong();
		final Executor bounceExecutor;

		State(ServerChunkCache scp) {
			this.bounceExecutor = scp.mainThreadProcessor;
		}

		@Nullable
		ChunkAccess lookup(long pos, ChunkStatus status) {
			int old = access.getAndIncrement();
			if (access.get() < old) {
				chunkCache.clear();
				return null;
			}
			ChunkCacheLine line = chunkCache.get(new ChunkCacheAddress(pos, status));
			if (line == null) {
				return null;
			}
			line.lastAccess = access.get();
			return line.chunk.get();
		}

		void store(long pos, ChunkStatus status, ChunkAccess chunk) {
			ChunkCacheAddress key = new ChunkCacheAddress(pos, status);
			ChunkCacheLine line = chunkCache.get(key);
			if (line == null) {
				chunkCache.put(key, new ChunkCacheLine(chunk, access.incrementAndGet()));
			} else {
				line.lastAccess = access.get();
				line.rebind(chunk);
			}
			if (stores.incrementAndGet() % EVICT_INTERVAL == 0 && chunkCache.size() > CACHE_SIZE) {
				evict();
			}
		}

		private void evict() {
			synchronized (this) {
				if (chunkCache.size() <= CACHE_SIZE) {
					return;
				}
				int max = access.get();
				int min = Integer.MAX_VALUE;
				for (ChunkCacheLine line : chunkCache.values()) {
					min = Math.min(min, line.lastAccess);
				}
				int cutoff = min + (int) ((max - min) / ((float) chunkCache.size() / CACHE_SIZE));
				chunkCache.entrySet().removeIf(e -> e.getValue().lastAccess < cutoff || e.getValue().chunk.get() == null);
			}
		}
	}

	static final class ChunkCacheAddress {
		private final long chunk;
		private final ChunkStatus status;

		ChunkCacheAddress(long chunk, ChunkStatus status) {
			this.chunk = chunk;
			this.status = status;
		}

		@Override
		public int hashCode() {
			return Long.hashCode(chunk) ^ status.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ChunkCacheAddress && ((ChunkCacheAddress) obj).chunk == chunk
					&& ((ChunkCacheAddress) obj).status.equals(status);
		}
	}

	static final class ChunkCacheLine {
		private WeakReference<ChunkAccess> chunk;
		volatile int lastAccess;

		ChunkCacheLine(ChunkAccess chunk, int lastAccess) {
			this.chunk = new WeakReference<>(chunk);
			this.lastAccess = lastAccess;
		}

		void rebind(ChunkAccess c) {
			if (chunk.get() == null) {
				chunk = new WeakReference<>(c);
			}
		}
	}
}
