package org.jmt.mcmt.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class GeneralConfig
{
    public static int threads = 4;
    public static boolean parallelWorld = true;
    public static boolean parallelEntities = true;
    public static boolean parallelTE = true;
    public static boolean parallelEnv = true;
    public static boolean threadChunks = false;

    public static void load(File file)
    {
        Configuration cfg = new Configuration(file);
        cfg.load();
        threads = cfg.getInt("threads", "general", 4, 1, 1024, "Parallelism pool size");
        parallelWorld = cfg.getBoolean("parallelWorld", "general", true, "Tick worlds in parallel");
        parallelEntities = cfg.getBoolean("parallelEntities", "general", true, "Tick entities in parallel");
        parallelTE = cfg.getBoolean("parallelTE", "general", true, "Tick tile entities in parallel");
        parallelEnv = cfg.getBoolean("parallelEnv", "general", true, "Tick environment processing per chunk in parallel");
        threadChunks = cfg.getBoolean("threadChunks", "general", false, "Allow chunk access from pool threads");
        if (cfg.hasChanged())
            cfg.save();
    }
}
