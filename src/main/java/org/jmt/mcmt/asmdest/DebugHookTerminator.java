package org.jmt.mcmt.asmdest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.config.GeneralConfig;

import com.mojang.datafixers.util.Either;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

// TODO Should be renamed ChunkRepairHookTerminator (Note requres coremod edit)
/**
 * Handles chunk forcing in scenarios where world corruption has occured
 *
 * @author jediminer543
 *
 */
public class DebugHookTerminator {

	private static final Logger LOGGER = LogManager.getLogger();

	private static boolean bypassLoadTarget = false;

	public static class BrokenChunkLocator {
		long chunkPos;
		CompletableFuture<?> maincf;
		CompletableFuture<?> brokecf;
		public BrokenChunkLocator(long chunkPos, CompletableFuture<?> maincf, CompletableFuture<?> brokecf) {
			super();
			this.chunkPos = chunkPos;
			this.maincf = maincf;
			this.brokecf = brokecf;
		}
		public long getChunkPos() {
			return chunkPos;
		}

	}

	public static List<BrokenChunkLocator> breaks = new ArrayList<>();

	public static boolean isBypassLoadTarget() {
		return bypassLoadTarget;
	}

	public static AtomicBoolean mainThreadChunkLoad = new AtomicBoolean();
	public static AtomicLong mainThreadChunkLoadCount = new AtomicLong();
	public static String mainThread = "Server thread";

	public static void chunkLoadDrive(ServerChunkCache scp, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> completableFuture, int chunkX, int chunkZ) {
		long chunkpos = ChunkPos.asLong(chunkX, chunkZ);
		int failcount = 0;
		if (Thread.currentThread().getName().equals(mainThread)) {
			mainThreadChunkLoadCount.set(0);
			mainThreadChunkLoad.set(true);
		}
		while (!completableFuture.isDone()) {
			if (!scp.pollTask()) {
				if (completableFuture.isDone()) {
					if (Thread.currentThread().getName().equals(mainThread)) {
						mainThreadChunkLoad.set(false);
					}
					break;
				}
				// Nothing more to execute
				if (!GeneralConfig.enableChunkTimeout || failcount++ < GeneralConfig.timeoutCount) {
					if (Thread.currentThread().getName().equals(mainThread)) {
						mainThreadChunkLoadCount.incrementAndGet();
					}
					Thread.yield();
					LockSupport.parkNanos(100000L);
				} else {
					LOGGER.error("", new TimeoutException("Error fetching chunk " + chunkpos));
					bypassLoadTarget = true;
					if (GeneralConfig.enableTimeoutRegen || GeneralConfig.enableBlankReturn) {
						ServerLevel level = (ServerLevel) scp.getLevel();
						if (GeneralConfig.enableBlankReturn) {
							// Generate a new empty chunk
							EmptyLevelChunk out = new EmptyLevelChunk(level, new ChunkPos(chunkX, chunkZ),
									level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
							// SCIENCE
							completableFuture.complete(Either.left((ChunkAccess)out));
						} else {
							try {
								CompoundTag cnbt = scp.chunkMap.readChunk(new ChunkPos(chunkX, chunkZ)).join().orElse(null);
								if (cnbt != null) {
									ProtoChunk cp = ChunkSerializer.read(level, level.getPoiManager(), new ChunkPos(chunkX, chunkZ), cnbt);
									completableFuture.complete(Either.left((ChunkAccess)new LevelChunk(level, cp, null)));
								} else {
									completableFuture.complete(ChunkHolder.UNLOADED_CHUNK);;
								}
							} catch (Exception e) {
								e.printStackTrace();
								completableFuture.complete(ChunkHolder.UNLOADED_CHUNK);;
							}
						}
					} else {
						System.err.println(completableFuture.toString());
						ChunkHolder chunkholder = scp.getVisibleChunkIfPresent(chunkpos);
						CompletableFuture<?> firstBroke = null;
						if (chunkholder != null) {
							for (ChunkStatus cs : ChunkStatus.getStatusList()) {
								CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> cf = chunkholder.getFutureIfPresentUnchecked(cs);
								if (cf == ChunkHolder.UNLOADED_CHUNK_FUTURE) {
									System.out.println("Status: " + cs.toString() + " is not yet loaded");
								} else {
									System.out.println("Status: " + cs.toString() + " is " + cf.toString());
									if (firstBroke == null && !cf.toString().contains("Completed normally")) {
										firstBroke = cf;
									}
								}
							}
						}
						breaks.add(new BrokenChunkLocator(chunkpos, completableFuture, firstBroke));
						completableFuture.complete(ChunkHolder.UNLOADED_CHUNK);;
					}
				}
			}
		}
	}

	public static void checkNull(Object o) {
		if (o == null) {
			System.out.println("Null warning:");
			new Throwable("Null trace").printStackTrace();
		}
	}
}
