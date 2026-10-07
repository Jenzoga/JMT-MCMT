package org.jmt.mcmt.asmdest;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicLong;

import java.lang.reflect.Constructor;

public class ASMHookTerminator
{
    private static volatile ForkJoinPool pool;
    private static final AtomicLong threadCounter = new AtomicLong();

    static
    {
        setupThreadpool(4);
    }

    public static void setupThreadpool(int threads)
    {
        if (threads < 1)
            threads = 1;
        pool = new ForkJoinPool(threads, r ->
        {
            try
            {
                Constructor<ForkJoinWorkerThread> c = ForkJoinWorkerThread.class.getDeclaredConstructor(ForkJoinPool.class);
                c.setAccessible(true);
                ForkJoinWorkerThread t = c.newInstance(r);
                t.setName("MCMT-Pool-Thread-" + threadCounter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
            catch (ReflectiveOperationException e)
            {
                return ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(r);
            }
        }, null, false);
    }

    public static boolean isThreadPooled()
    {
        ForkJoinPool p = pool;
        return Thread.currentThread().getName().startsWith("MCMT-Pool-Thread-");
    }

    public static int poolSize()
    {
        ForkJoinPool p = pool;
        return p == null ? 0 : p.getParallelism();
    }
}
