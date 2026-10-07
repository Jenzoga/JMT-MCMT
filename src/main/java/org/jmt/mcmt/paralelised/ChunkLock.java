package org.jmt.mcmt.paralelised;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class ChunkLock
{
    public static final ChunkLock INSTANCE = new ChunkLock();

    private final Map<Long, ReentrantLock> chunkLockCache = new ConcurrentHashMap<>();

    public static long key(int chunkX, int chunkZ)
    {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public long[] lock(int chunkX, int chunkZ, int radius)
    {
        long[] targets = new long[(1 + radius * 2) * (1 + radius * 2)];
        int pos = 0;
        for (int i = -radius; i <= radius; i++)
        {
            for (int j = -radius; j <= radius; j++)
            {
                targets[pos++] = key(chunkX + i, chunkZ + j);
            }
        }
        Arrays.sort(targets);
        for (long l : targets)
        {
            chunkLockCache.computeIfAbsent(l, x -> new ReentrantLock()).lock();
        }
        return targets;
    }

    public void unlock(long[] locks)
    {
        for (int i = locks.length - 1; i >= 0; i--)
        {
            ReentrantLock lock = chunkLockCache.get(locks[i]);
            if (lock != null)
                lock.unlock();
        }
    }
}
