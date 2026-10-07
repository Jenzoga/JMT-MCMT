package org.jmt.mcmt;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.asmdest.ASMHookTerminator;
import org.jmt.mcmt.commands.CommandMCMT;
import org.jmt.mcmt.config.GeneralConfig;

@Mod(modid = Constants.MOD_ID, name = Constants.MOD_NAME, version = Constants.VERSION,
        acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public class MCMT
{
    public static final Logger LOGGER = LogManager.getLogger(Constants.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        GeneralConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event)
    {
        event.registerServerCommand(new CommandMCMT());
        ASMHookTerminator.setupThreadpool(GeneralConfig.threads);
    }
}
