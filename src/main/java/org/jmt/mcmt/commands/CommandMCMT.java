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
        }
        else
        {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
        }
    }
}
