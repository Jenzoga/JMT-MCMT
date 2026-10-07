package org.jmt.mcmt.config;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

public class GeneralConfig
{
    public static int threads = 4;
    public static boolean parallelWorld = true;
    public static boolean parallelEntities = true;
    public static boolean parallelTE = true;
    public static boolean parallelEnv = false;
    public static boolean threadChunks = false;
    public static volatile boolean disabled = false;
    public static boolean opsTracing = false;
    public static boolean chunkLockModded = true;
    public static Set<Class<?>> teWhiteList = new HashSet<Class<?>>();
    public static Set<Class<?>> teBlackList = new HashSet<Class<?>>();

    public static void load(File file)
    {
        Configuration cfg = new Configuration(file);
        cfg.load();
        threads = cfg.getInt("threads", "general", 4, 1, 1024, "Parallelism pool size");
        parallelWorld = cfg.getBoolean("parallelWorld", "general", true, "Tick worlds in parallel");
        parallelEntities = cfg.getBoolean("parallelEntities", "general", true, "Tick entities in parallel");
        parallelTE = cfg.getBoolean("parallelTE", "general", true, "Tick tile entities in parallel");
        parallelEnv = cfg.getBoolean("parallelEnv", "general", false, "Tick environment processing per chunk in parallel. Unsafe on fresh worlds: pool-thread block updates race WorldServer's scheduledEventsForTickList (TickNextTick list out of synch). Needs serdes first.");
        threadChunks = cfg.getBoolean("threadChunks", "general", false, "Allow chunk access from pool threads");
        if (cfg.hasChanged())
            cfg.save();
    }
}
