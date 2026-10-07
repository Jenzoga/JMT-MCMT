package org.jmt.mcmt.asmdest;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Constructor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.MCMT;
import org.jmt.mcmt.config.GeneralConfig;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityPiston;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * All ASM hooks injected by the coremod transformers terminate here.
 *
 * Do not rename this class; injected bytecode references it by name.
 * Be careful what is referenced from static/instance initializers: class
 * loading during early boot is fragile (see 1.16 history notes).
 */
public class ASMHookTerminator
{
    private static final Logger LOGGER = LogManager.getLogger();

    static Phaser p;
    static ExecutorService ex;
    static MinecraftServer mcs;
    static AtomicBoolean isTicking = new AtomicBoolean();
    static AtomicInteger threadID = new AtomicInteger();
    static long tickStart = 0;

    static
    {
        // Must be static here due to class loading shenanigans
        setupThreadpool(4);
    }

    public static void setupThreadpool(int parallelism)
    {
        threadID = new AtomicInteger();
        final ClassLoader cl = MCMT.class.getClassLoader();
        ex = new ForkJoinPool(parallelism, pool ->
        {
            try
            {
                Constructor<ForkJoinWorkerThread> c = ForkJoinWorkerThread.class.getDeclaredConstructor(ForkJoinPool.class);
                c.setAccessible(true);
                ForkJoinWorkerThread t = c.newInstance(pool);
                t.setName("MCMT-Pool-Thread-" + threadID.getAndIncrement());
                t.setDaemon(true);
                t.setContextClassLoader(cl);
                regThread("MCMT", t);
                return t;
            }
            catch (ReflectiveOperationException e)
            {
                return ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            }
        }, null, false);
    }

    static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<String, Set<Thread>>();

    // Statistics
    public static AtomicInteger currentWorlds = new AtomicInteger();
    public static AtomicInteger currentEnts = new AtomicInteger();
    public static AtomicInteger currentTEs = new AtomicInteger();
    public static AtomicInteger currentEnvs = new AtomicInteger();

    // Operation logging
    public static Set<String> currentTasks = ConcurrentHashMap.newKeySet();

    public static void regThread(String poolName, Thread thread)
    {
        mcThreadTracker.computeIfAbsent(poolName, s -> ConcurrentHashMap.newKeySet()).add(thread);
    }

    public static boolean isThreadPooled(String poolName, Thread t)
    {
        return mcThreadTracker.containsKey(poolName) && mcThreadTracker.get(poolName).contains(t);
    }

    public static boolean serverExecutionThreadPatch(MinecraftServer ms)
    {
        return isThreadPooled("MCMT", Thread.currentThread());
    }

    /**
     * Called at the top of MinecraftServer.updateTimeLightAndEntities().
     * Awaits the previous tick's world tasks (no separate postTick hook is
     * injected on 1.12) and opens a fresh phaser for this tick.
     */
    public static void preTick(MinecraftServer server)
    {
        if (p != null)
        {
            p.arriveAndAwaitAdvance();
        }
        else
        {
            tickStart = System.nanoTime();
            mcs = server;
        }
        isTicking.set(true);
        p = new Phaser();
        p.register();
    }

    public static void callTick(WorldServer world, MinecraftServer server)
    {
        if (GeneralConfig.disabled || !GeneralConfig.parallelWorld || p == null)
        {
            world.tick();
            return;
        }
        if (mcs != server)
        {
            LOGGER.warn("Multiple servers?");
            GeneralConfig.disabled = true;
            world.tick();
            return;
        }
        String taskName = null;
        if (GeneralConfig.opsTracing)
        {
            taskName = "WorldTick: " + world.toString() + "@" + world.hashCode();
            currentTasks.add(taskName);
        }
        String finalTaskName = taskName;
        p.register();
        ex.execute(() ->
        {
            try
            {
                currentWorlds.incrementAndGet();
                world.tick();
            }
            finally
            {
                p.arriveAndDeregister();
                currentWorlds.decrementAndGet();
                if (GeneralConfig.opsTracing)
                    currentTasks.remove(finalTaskName);
            }
        });
    }

    public static void callEntityTick(Entity entityIn, World world)
    {
        if (GeneralConfig.disabled || !GeneralConfig.parallelEntities)
        {
            entityIn.onUpdate();
            return;
        }
        String taskName = null;
        if (GeneralConfig.opsTracing)
        {
            taskName = "EntityTick: " + entityIn.toString() + "@" + entityIn.hashCode();
            currentTasks.add(taskName);
        }
        String finalTaskName = taskName;
        p.register();
        ex.execute(() ->
        {
            try
            {
                currentEnts.incrementAndGet();
                entityIn.onUpdate();
            }
            finally
            {
                currentEnts.decrementAndGet();
                p.arriveAndDeregister();
                if (GeneralConfig.opsTracing)
                    currentTasks.remove(finalTaskName);
            }
        });
    }

    public static boolean filterTE(TileEntity te)
    {
        boolean isLocking = GeneralConfig.teBlackList.contains(te.getClass());
        // A string startsWith check is faster than Class.getPackage
        if (!isLocking && GeneralConfig.chunkLockModded && !te.getClass().getName().startsWith("net.minecraft.tileentity."))
            isLocking = true;
        if (isLocking && GeneralConfig.teWhiteList.contains(te.getClass()))
            isLocking = false;
        if (te instanceof TileEntityPiston)
            isLocking = true;
        return isLocking;
    }

    // NOTE: 1.12 has no central TileEntity tick loop (no World.tickBlockEntities
    // equivalent; TEs dispatch per-block via scheduled ticks). A TE parallel
    // hook needs a different injection strategy and is deferred.

    public static long[] lastTickTime = new long[32];
    public static int lastTickTimePos = 0;
    public static int lastTickTimeFill = 0;

    public static void postTick(MinecraftServer server)
    {
        if (mcs != server)
        {
            LOGGER.warn("Multiple servers?");
            return;
        }
        p.arriveAndAwaitAdvance();
        isTicking.set(false);
        p = null;
        lastTickTime[lastTickTimePos] = System.nanoTime() - tickStart;
        lastTickTimePos = (lastTickTimePos + 1) % lastTickTime.length;
        lastTickTimeFill = Math.min(lastTickTimeFill + 1, lastTickTime.length - 1);
    }

    public static String populateCrashReport()
    {
        StringBuilder confInfo = new StringBuilder();
        confInfo.append("\n");
        confInfo.append("\t\t"); confInfo.append("Config Info:"); confInfo.append("\n");
        confInfo.append("\t\t"); confInfo.append("\t- Disabled: ");
        confInfo.append(GeneralConfig.disabled); confInfo.append("\n");
        confInfo.append("\t\t"); confInfo.append("\t- World Parallel: ");
        confInfo.append(GeneralConfig.parallelWorld); confInfo.append("\n");
        confInfo.append("\t\t"); confInfo.append("\t- Entity Parallel: ");
        confInfo.append(GeneralConfig.parallelEntities); confInfo.append("\n");
        confInfo.append("\t\t"); confInfo.append("\t- Env Parallel: ");
        confInfo.append(GeneralConfig.parallelEnv); confInfo.append("\n");
        confInfo.append("\t\t"); confInfo.append("\t- TE Parallel: ");
        confInfo.append(GeneralConfig.parallelTE); confInfo.append("\n");
        if (GeneralConfig.opsTracing)
        {
            confInfo.append("\t\t"); confInfo.append("-- Running Operations Begin -- "); confInfo.append("\n");
            for (String s : currentTasks)
            {
                confInfo.append("\t\t"); confInfo.append("\t"); confInfo.append(s); confInfo.append("\n");
            }
            confInfo.append("\t\t"); confInfo.append("-- Running Operations End -- "); confInfo.append("\n");
        }
        return confInfo.toString();
    }

    public static boolean shouldThreadChunks()
    {
        return GeneralConfig.threadChunks;
    }

    public static int poolSize()
    {
        ExecutorService e = ex;
        return e instanceof ForkJoinPool ? ((ForkJoinPool) e).getParallelism() : 0;
    }
}
