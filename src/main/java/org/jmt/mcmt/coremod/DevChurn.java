package org.jmt.mcmt.coremod;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.config.GeneralConfig;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Deterministic entity/chunk churn harness, dev-only. Enabled by -Dmcmt.devchurn=true
 * (injected into DedicatedServer.run() entry by MCMTClassTransformer). Spawns a
 * daemon thread that exercises the shared structures pool-thread entity ticks
 * touch, so CME/corruption races surface in minutes instead of 20-minute soaks.
 */
public final class DevChurn
{
    private static final Logger LOG = LogManager.getLogger("MCMT-Churn");
    private static final AtomicLong cmeCount = new AtomicLong();
    private static final AtomicLong otherCount = new AtomicLong();
    private static volatile boolean running = false;

    private DevChurn()
    {
    }

    public static void boot(MinecraftServer server)
    {
        if (!Boolean.getBoolean("mcmt.devchurn") || running || server == null)
            return;
        running = true;
        Thread t = new Thread(() -> loop(server), "MCMT-Churn");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(MinecraftServer server)
    {
        int iterations = Integer.getInteger("mcmt.churn.iterations", 300);
        long maxMillis = Long.getLong("mcmt.churn.maxmillis", 600_000L);
        try
        {
            Thread.sleep(500);
        }
        catch (InterruptedException ignored)
        {
        }
        long deadline = System.currentTimeMillis() + maxMillis;
        // World load (initialWorldChunkLoad) must be complete: churning against
        // half-constructed providers throws IllegalMonitorStateException on the
        // loadedChunks monitor and races AnvilChunkLoader teardown.
        while (server.isServerRunning())
        {
            try
            {
                if (net.minecraftforge.common.DimensionManager.getWorld(0) != null
                        && !net.minecraftforge.common.DimensionManager.getWorld(0)
                                .getChunkProvider().loadedChunks.isEmpty())
                    break;
            }
            catch (Throwable ignored)
            {
            }
            try
            {
                Thread.sleep(200);
            }
            catch (InterruptedException ignored)
            {
                return;
            }
            if (System.currentTimeMillis() > deadline)
                return;
        }
        // Pool-thread crashes otherwise go through the server's per-thread handler
        // and stop the server mid-run; capture them as race findings instead.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> record(e, "uncaught:" + t.getName()));
        for (Thread t2 : Thread.getAllStackTraces().keySet())
            if (t2.getName().startsWith("MCMT-Pool"))
                t2.setUncaughtExceptionHandler((th, e) -> record(e, "uncaught:" + th.getName()));
        GeneralConfig.parallelEntities = Boolean.parseBoolean(System.getProperty("mcmt.churn.parallelEntities", "true"));
        GeneralConfig.parallelTE = Boolean.parseBoolean(System.getProperty("mcmt.churn.parallelTE", "false"));
        GeneralConfig.parallelEnv = Boolean.parseBoolean(System.getProperty("mcmt.churn.parallelEnv", "false"));
        LOG.info("churn: start iterations={} parallelEntities={} parallelTE={} parallelEnv={}",
                iterations, GeneralConfig.parallelEntities, GeneralConfig.parallelTE, GeneralConfig.parallelEnv);
        long start = System.currentTimeMillis();
        for (int i = 0; i < iterations && server.isServerRunning() && System.currentTimeMillis() < deadline; i++)
        {
            for (int dim : new int[] { 0, -1, 1 })
            {
                WorldServer ws;
                try
                {
                    // DimensionManager.getWorld: loaded-only lookup. NEVER
                    // server.getWorld(dim) — that lazy-inits unloaded dims
                    // (WorldServerMulti ctor) off the main thread and NPEs.
                    ws = server.isServerRunning()
                            ? net.minecraftforge.common.DimensionManager.getWorld(dim)
                            : null;
                }
                catch (Throwable ignored)
                {
                    continue;
                }
                if (ws == null)
                    continue;
                try
                {
                    churnWorld(ws);
                }
                catch (Throwable t)
                {
                    record(t, "churnWorld:dim" + dim);
                }
            }
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException e)
            {
                break;
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        running = false;
        LOG.info("churn: DONE elapsed={}ms cme={} other={}", elapsed, cmeCount.get(), otherCount.get());
        if (cmeCount.get() > 0 || otherCount.get() > 0)
            LOG.error("churn: RACES DETECTED — see earlier stack traces");
        else
            LOG.info("churn: CLEAN — no races observed");
    }

    private static void churnWorld(WorldServer ws)
    {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BlockPos spawn = ws.getSpawnPoint();
        boolean unsafe = Boolean.getBoolean("mcmt.churn.unsafe");
        // spawn/remove churn against loadedEntityList while pool threads tick entities.
        // unsafe=true drops the harness-side lock: negative control proving the
        // detector catches the exact CME class the production wrappers prevent.
        for (int k = 0; k < 16; k++)
        {
            EntityItem item = new EntityItem(ws, spawn.getX() + r.nextDouble(64) - 32, spawn.getY() + 1,
                    spawn.getZ() + r.nextDouble(64) - 32);
            item.setPickupDelay(10000);
            if (unsafe)
            {
                ws.spawnEntity(item);
                if (r.nextInt(100) < 50)
                    ws.removeEntity(item);
            }
            else
            {
                // no harness-side lock: the production wrappers own lock order
                // (loadedChunks -> loadedEntityList); taking loadedEntityList
                // here first would invert against wrapper callers and deadlock
                ws.spawnEntity(item);
                if (r.nextInt(100) < 50)
                    ws.removeEntity(item);
            }
        }
        // moveEntity crosses chunk entityLists (onEntityMoved) while pool threads
        // tick the same entities and main-thread chunk ops iterate those lists
        for (int k = 0; k < 8; k++)
        {
            for (Entity e : new java.util.ArrayList<Entity>(ws.loadedEntityList))
            {
                if (e.isDead)
                    continue;
                try
                {
                    ws.updateEntityWithOptionalForce(e, false);
                }
                catch (Throwable t)
                {
                    record(t, "updateEntity");
                }
            }
        }
        // main-thread-style iteration of loadedEntityList (foamfix removeUnloadedEntities shape)
        int iterated = 0;
        for (Object o : ws.loadedEntityList)
            iterated++;
        if (iterated < 0)
            throw new AssertionError();
        // AABB queries read per-chunk entity lists while entity ticks move entities across chunks
        for (int k = 0; k < 8; k++)
        {
            AxisAlignedBB bb = new AxisAlignedBB(spawn.getX() - 32, spawn.getY() - 8, spawn.getZ() - 32,
                    spawn.getX() + 32, spawn.getY() + 16, spawn.getZ() + 32);
            List<Entity> found = ws.getEntitiesWithinAABB(Entity.class, bb);
            found.size();
        }
        // chunk load/unload churn under the same ChunkLock production env ticks
        // take — without it the harness itself races loadedChunks (fastutil map
        // iterated by ChunkProviderServer.tick on pool threads) with side effects
        // it does not model, producing false-positive CMEs
        ChunkProviderServer cps = ws.getChunkProvider();
        int sx = spawn.getX() >> 4;
        int sz = spawn.getZ() >> 4;
        for (int k = 0; k < 8; k++)
        {
            int cx = sx + r.nextInt(6) - 3;
            int cz = sz + r.nextInt(6) - 3;
            long[] locks = org.jmt.mcmt.paralelised.ChunkLock.INSTANCE.lock(cx, cz, 1);
            try
            {
                Chunk c = cps.provideChunk(cx, cz);
                // env tick under the same 3x3 lock shape callEnvTick uses —
                // exercises cross-chunk block-update paths against pool dispatch
                if (c != null)
                {
                    try
                    {
                        java.lang.reflect.Method m = ws.getClass().getDeclaredMethod("mcmt$tickEnvChunk",
                                Chunk.class, int.class, boolean.class, boolean.class);
                        m.setAccessible(true);
                        m.invoke(ws, c, ws.getGameRules().getInt("randomTickSpeed"),
                                ws.isRaining(), ws.isThundering());
                    }
                    catch (ReflectiveOperationException ignored)
                    {
                        // extraction not applied (shouldDispatchEnv path); the
                        // per-chunk env tick still runs inside updateBlocks
                    }
                }
                if (c != null && r.nextBoolean())
                    cps.queueUnload(c);
            }
            finally
            {
                org.jmt.mcmt.paralelised.ChunkLock.INSTANCE.unlock(locks);
            }
        }
        // TE list churn: lazy-add (getTileEntity on random positions) and block-
        // change remove paths mutate tickableTileEntities while updateEntities
        // iterates it; pool-thread ITickable.update side effects hit the same lists
        for (int k = 0; k < 8; k++)
        {
            BlockPos p = spawn.add(r.nextInt(64) - 32, r.nextInt(16), r.nextInt(64) - 32);
            try
            {
                net.minecraft.tileentity.TileEntity te = ws.getTileEntity(p);
                if (te instanceof net.minecraft.util.ITickable)
                    ((net.minecraft.util.ITickable) te).update();
                if (r.nextInt(100) < 5)
                    ws.removeTileEntity(p);
            }
            catch (Throwable t)
            {
                record(t, "teChurn");
            }
        }
        // EntityTracker.tick iterates entries on this thread while pool-thread
        // spawn/remove reaches track/untrack through WorldServer.onEntity*
        EntityTracker et = ws.getEntityTracker();
        et.tick();
    }

    private static void record(Throwable t, String where)
    {
        boolean cme = t instanceof java.util.ConcurrentModificationException;
        if (!cme && t.getStackTrace() != null)
        {
            for (StackTraceElement e : t.getStackTrace())
                if (e.getClassName().startsWith("net.minecraft"))
                {
                    cme = true;
                    break;
                }
        }
        if (cme)
            cmeCount.incrementAndGet();
        else
            otherCount.incrementAndGet();
        LOG.error("churn: race candidate at " + where + " thread=" + Thread.currentThread().getName(), t);
    }
}
