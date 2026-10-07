package org.jmt.mcmt.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import org.jmt.mcmt.asmdest.ASMHookTerminator;

public class CommandMCMT extends CommandBase
{
    @Override
    public String getName()
    {
        return "mcmt";
    }

    @Override
    public String getUsage(ICommandSender sender)
    {
        return "/mcmt stats";
    }

    @Override
    public int getRequiredPermissionLevel()
    {
        return 4;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException
    {
        if (args.length > 0 && args[0].equals("stats"))
        {
            sender.sendMessage(new TextComponentString("MCMT pool size: " + ASMHookTerminator.poolSize()));
            sender.sendMessage(new TextComponentString("In-flight: worlds=" + ASMHookTerminator.currentWorlds.get()
                    + " ents=" + ASMHookTerminator.currentEnts.get()
                    + " tes=" + ASMHookTerminator.currentTEs.get()
                    + " envs=" + ASMHookTerminator.currentEnvs.get()));
            long avg = 0;
            int fill = ASMHookTerminator.lastTickTimeFill;
            if (fill > 0)
            {
                long sum = 0;
                for (int i = 0; i < fill; i++)
                    sum += ASMHookTerminator.lastTickTime[i];
                avg = sum / fill / 1000000L;
            }
            sender.sendMessage(new TextComponentString("Avg tick (last " + fill + "): " + avg + " ms"));
        }
        else
        {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
        }
    }
}
