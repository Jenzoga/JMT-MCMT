package org.jmt.mcmt.asmdest;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.MCMT;
import org.jmt.mcmt.commands.StatsCommand;
import org.jmt.mcmt.config.GeneralConfig;
import org.jmt.mcmt.serdes.SerDesHookTypes;
import org.jmt.mcmt.serdes.SerDesRegistry;
import org.jmt.mcmt.serdes.filter.ISerDesFilter;
import org.jmt.mcmt.serdes.pools.PostExecutePool;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.CrashReportCallables;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.common.MinecraftForge;

/**
 * This is where all current ASM hooks are terminated
 *
 * So DON'T rename this file (Or there will be a lot of other work todo)
 *
 * Fun point: So because this is hooking into a lot of the stuff, be careful what you reference here
 * I attempted to reference a function on {@link GeneralConfig} and it got VERY angery at me with a "class refuses to load" error
 * So remember that if you start getting class loading errors
 *
 *
 * TODO: Add more docs
 *
 * @author jediminer543
 *
 */
public class ASMHookTerminator {

	private static final Logger LOGGER = LogManager.getLogger();

	static Phaser p;
	static ExecutorService ex;
	static MinecraftServer mcs;
	static AtomicBoolean isTicking = new AtomicBoolean();
	static AtomicInteger threadID = new AtomicInteger();

	public static void setupThreadpool(int parallelism) {
		threadID = new AtomicInteger();
		final ClassLoader cl = MCMT.class.getClassLoader();
		ForkJoinWorkerThreadFactory fjpf = p -> {
			ForkJoinWorkerThread fjwt = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
			fjwt.setName("MCMT-Pool-Thread-"+threadID.getAndIncrement());
			regThread("MCMT", fjwt);
			fjwt.setContextClassLoader(cl);
			return fjwt;
		};
		ex = new ForkJoinPool(
				parallelism,
				 fjpf,
	             null, false);
	}

	/**
	 * Creates and sets up the thread pool
	 */
	static {
		// Must be static here due to class loading shenanagins
		setupThreadpool(4);
	}

	static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<String, Set<Thread>>();

	// Statistics
	public static AtomicInteger currentWorlds = new AtomicInteger();
	public static AtomicInteger currentEnts = new AtomicInteger();
	public static AtomicInteger currentTEs = new AtomicInteger();
	public static AtomicInteger currentEnvs = new AtomicInteger();

	//Operation logging
	public static Set<String> currentTasks = ConcurrentHashMap.newKeySet();

	public static void regThread(String poolName, Thread thread) {
		mcThreadTracker.computeIfAbsent(poolName, s -> ConcurrentHashMap.newKeySet()).add(thread);
	}

	public static boolean isThreadPooled(String poolName, Thread t) {
		return mcThreadTracker.containsKey(poolName) && mcThreadTracker.get(poolName).contains(t);
	}

	static long tickStart = 0;

	public static void preTick(MinecraftServer server) {
		if (p != null) {
			LOGGER.warn("Multiple servers?");
			return;
		} else {
			tickStart = System.nanoTime();
			isTicking.set(true);
			p = new Phaser();
			p.register();
			mcs = server;
			StatsCommand.setServer(mcs);
		}
	}

	public static void callTick(ServerLevel serverworld, BooleanSupplier hasTimeLeft, MinecraftServer server) {
		if (GeneralConfig.disabled || GeneralConfig.disableWorld) {
			try {
				serverworld.tick(hasTimeLeft);
			} finally {
				MinecraftForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(LogicalSide.SERVER, TickEvent.Phase.END, serverworld, hasTimeLeft));
			}
			return;
		}
		if (mcs != server) {
			LOGGER.warn("Multiple servers?");
			GeneralConfig.disabled = true;
			serverworld.tick(hasTimeLeft);
			MinecraftForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(LogicalSide.SERVER, TickEvent.Phase.END, serverworld, hasTimeLeft));
			return;
		} else {
			String taskName = null;
			if (GeneralConfig.opsTracing) {
				 taskName =  "WorldTick: " + serverworld.toString() + "@" + serverworld.hashCode();
				currentTasks.add(taskName);
			}
			String finalTaskName = taskName;
			p.register();
			ex.execute(() -> {
				try {
					currentWorlds.incrementAndGet();
					serverworld.tick(hasTimeLeft);
					if (GeneralConfig.disableWorldPostTick) {
						synchronized (MinecraftForge.EVENT_BUS) {
							MinecraftForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(LogicalSide.SERVER, TickEvent.Phase.END, serverworld, hasTimeLeft));
						}
					} else {
						MinecraftForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(LogicalSide.SERVER, TickEvent.Phase.END, serverworld, hasTimeLeft));
					}
				} finally {
					p.arriveAndDeregister();
					currentWorlds.decrementAndGet();
					if (GeneralConfig.opsTracing) currentTasks.remove(finalTaskName);
				}
			});
		}
	}

	public static void callEntityTick(Entity entityIn, ServerLevel serverworld) {
		if (GeneralConfig.disabled || GeneralConfig.disableEntity) {
			entityIn.tick();
			return;
		}
		String taskName = null;
		if (GeneralConfig.opsTracing) {
			taskName = "EntityTick: " + entityIn.toString() + "@" + entityIn.hashCode();
			currentTasks.add(taskName);
		}
		String finalTaskName = taskName;
		p.register();
		ex.execute(() -> {
			try {
				final ISerDesFilter filter = SerDesRegistry.getFilter(SerDesHookTypes.EntityTick, entityIn.getClass());
				currentTEs.incrementAndGet();
				if (filter != null) {
					filter.serialise(entityIn::tick, entityIn, entityIn.blockPosition(), serverworld, SerDesHookTypes.EntityTick);
				} else {
					entityIn.tick();
				}
			} finally {
				currentEnts.decrementAndGet();
				p.arriveAndDeregister();
				if (GeneralConfig.opsTracing) currentTasks.remove(finalTaskName);
			}
		});
	}

	public static void callTickEnvironment(ServerLevel world, LevelChunk chunk, int k, ServerChunkCache scp) {
		if (GeneralConfig.disabled  || GeneralConfig.disableEnvironment) {
			world.tickChunk(chunk, k);
			return;
		}
		String taskName = null;
		if (GeneralConfig.opsTracing) {
			taskName = "EnvTick: " + chunk.toString() + "@" + chunk.hashCode();
			currentTasks.add(taskName);
		}
		String finalTaskName = taskName;
		p.register();
		ex.execute(() -> {
			try {
				currentEnvs.incrementAndGet();
				world.tickChunk(chunk, k);
			} finally {
				currentEnvs.decrementAndGet();
				p.arriveAndDeregister();
				if (GeneralConfig.opsTracing) currentTasks.remove(finalTaskName);
			}
		});
	}

	public static boolean filterTE(BlockEntity tte) {
		boolean isLocking = false;
		if (GeneralConfig.teBlackList.contains(tte.getClass())) {
			isLocking = true;
		}
		// Apparently a string starts with check is faster than Class.getPackage; who knew (I didn't)
		if (!isLocking && GeneralConfig.chunkLockModded && !tte.getClass().getName().startsWith("net.minecraft.world.level.block.entity.")) {
			isLocking = true;
		}
		if (isLocking && GeneralConfig.teWhiteList.contains(tte.getClass())) {
			isLocking = false;
		}
		if (tte instanceof PistonMovingBlockEntity) {
			isLocking = true;
		}
		return isLocking;
	}

	/**
	 * Returns true if the tick was taken over (dispatched or run inline);
	 * false means the caller should execute the original ticker itself.
	 */
	public static boolean callTileEntityTick(TickingBlockEntity tbe, Level world) {
		if (GeneralConfig.disabled || GeneralConfig.disableTileEntity || !(world instanceof ServerLevel)) {
			return false;
		}
		if (TE_TICK_GUARD.get()) {
			// We are already running inside a dispatched BE tick; run inline to avoid recursion
			return false;
		}
		String taskName = null;
		if (GeneralConfig.opsTracing) {
			taskName = "TETick: " + tbe.toString() + "@" + tbe.hashCode();
			currentTasks.add(taskName);
		}
		String finalTaskName = taskName;
		p.register();
		ex.execute(() -> {
			TE_TICK_GUARD.set(Boolean.TRUE);
			try {
				BlockEntity be = getBlockEntityFromWrapper(tbe);
				final ISerDesFilter filter = SerDesRegistry.getFilter(SerDesHookTypes.TETick, be != null ? be.getClass() : tbe.getClass());
				currentTEs.incrementAndGet();
				if (filter != null && be != null) {
					filter.serialise(tbe::tick, be, be.getBlockPos(), world, SerDesHookTypes.TETick);
				} else {
					tbe.tick();
				}
			} catch (Exception e) {
				System.err.println("Exception ticking TE at " + tbe.getPos());
				e.printStackTrace();
			} finally {
				TE_TICK_GUARD.remove();
				currentTEs.decrementAndGet();
				p.arriveAndDeregister();
				if (GeneralConfig.opsTracing) currentTasks.remove(finalTaskName);
			}
		});
		return true;
	}

	private static final ThreadLocal<Boolean> TE_TICK_GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private static BlockEntity getBlockEntityFromWrapper(TickingBlockEntity tbe) {
		try {
			return BE_FIELD.get(tbe) instanceof BlockEntity ? (BlockEntity) BE_FIELD.get(tbe) : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static java.lang.reflect.Field BE_FIELD;
	static {
		try {
			Class<?> clazz = Class.forName("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity");
			BE_FIELD = clazz.getDeclaredField("blockEntity");
			BE_FIELD.setAccessible(true);
		} catch (Throwable t) {
			BE_FIELD = null;
		}
	}

	/**
	 * Reimplementation of ServerLevel#runBlockEvents for the parallelised world.
	 * Duplicates vanilla logic so the hook can run the queue from our thread context.
	 */
	public static void runQueuedBlockEvents(ServerLevel sw) {
		ArrayDeque<BlockEventData> reschedule = new ArrayDeque<>();
		while (!sw.blockEvents.isEmpty()) {
			BlockEventData blockeventdata = sw.blockEvents.removeFirst();
			if (sw.shouldTickBlocksAt(blockeventdata.pos())) {
				if (!sw.doBlockEvent(blockeventdata)) {
					continue;
				}
				sw.getServer().getPlayerList().broadcast(null, blockeventdata.pos().getX(), blockeventdata.pos().getY(), blockeventdata.pos().getZ(), 64.0D, sw.dimension(), new net.minecraft.network.protocol.game.ClientboundBlockEventPacket(blockeventdata.pos(), blockeventdata.block(), blockeventdata.paramA(), blockeventdata.paramB()));
			} else {
				reschedule.add(blockeventdata);
			}
			if (!isTicking.get()) {
				LOGGER.fatal("Block updates outside of tick");
			}
		}
		sw.blockEvents.addAll(reschedule);
	}

	public static long[] lastTickTime = new long[32];
	public static int lastTickTimePos = 0;
	public static int lastTickTimeFill = 0;

	public static void postTick(MinecraftServer server) {
		if (mcs != server) {
			LOGGER.warn("Multiple servers?");
			return;
		} else {
			p.arriveAndAwaitAdvance();
			isTicking.set(false);
			p = null;
			//PostExecute logic
			Deque<Runnable> queue = PostExecutePool.POOL.getQueue();
			Iterator<Runnable> qi = queue.iterator();
			while (qi.hasNext()) {
				Runnable r = qi.next();
				r.run();
				qi.remove();
			}
			lastTickTime[lastTickTimePos] = System.nanoTime() - tickStart;
			lastTickTimePos = (lastTickTimePos+1)%lastTickTime.length;
			lastTickTimeFill = Math.min(lastTickTimeFill+1, lastTickTime.length-1);
		}
	}

	public static String populateCrashReport() {
		StringBuilder confInfo = new StringBuilder();
		confInfo.append("\n");
		confInfo.append("\t\t"); confInfo.append("Config Info:"); confInfo.append("\n");
		confInfo.append("\t\t"); confInfo.append("\t- Disabled: ");
		confInfo.append(GeneralConfig.disabled); confInfo.append("\n");
		confInfo.append("\t\t"); confInfo.append("\t- World Disabled: ");
		confInfo.append(GeneralConfig.disableWorld);
		confInfo.append("(onPostTick Disabled: "); confInfo.append(GeneralConfig.disableWorldPostTick); confInfo.append(")\n");
		confInfo.append("\t\t"); confInfo.append("\t- Entity Disabled: ");
		confInfo.append(GeneralConfig.disableEntity); confInfo.append("\n");
		confInfo.append("\t\t"); confInfo.append("\t- Env Disabled: ");
		confInfo.append(GeneralConfig.disableEnvironment); confInfo.append("\n");
		confInfo.append("\t\t"); confInfo.append("\t- TE Disabled: ");
		confInfo.append(GeneralConfig.disableTileEntity); confInfo.append("\n");
		confInfo.append("\t\t"); confInfo.append("\t- SCP Disabled: ");
		confInfo.append(GeneralConfig.disableChunkProvider); confInfo.append("\n");
		//TODO expand on TE settings
		if (GeneralConfig.opsTracing) {
			confInfo.append("\t\t"); confInfo.append("-- Running Operations Begin -- "); confInfo.append("\n");
			for (String s : currentTasks) {
				confInfo.append("\t\t"); confInfo.append("\t"); confInfo.append(s); confInfo.append("\n");
			}
			confInfo.append("\t\t"); confInfo.append("-- Running Operations End -- "); confInfo.append("\n");
		}
		return confInfo.toString();
	}

	static {
		CrashReportCallables.registerCrashCallable("MCMT", ASMHookTerminator::populateCrashReport);
	}

	public static boolean shouldThreadChunks() {
		return GeneralConfig.disableMultiChunk;
	}
}
