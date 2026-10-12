package org.jmt.mcmt.commands;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.jmt.mcmt.asmdest.ASMHookTerminator;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class PerfCommand {

	public static LiteralArgumentBuilder<CommandSourceStack> registerPerf(LiteralArgumentBuilder<CommandSourceStack> root) {
		return root.then(Commands.literal("mspt").executes(cmdCtx -> {
			try {
				double mspt = Arrays.stream(ASMHookTerminator.lastTickTime)
						.boxed().collect(Collectors.toList()).subList(0, ASMHookTerminator.lastTickTimeFill)
						.stream().mapToDouble(i->i/1000000.0).average().orElse(0);
				double last = ASMHookTerminator.lastTickTime[(ASMHookTerminator.lastTickTimePos-1)%ASMHookTerminator.lastTickTime.length]/1000000.0;
				Component message = Component.literal(
						"Average MSPT was " + mspt + "ms (Last: " + last + "ms)");
				cmdCtx.getSource().sendSuccess(() -> message, true);
			} catch (Exception e) {
				e.printStackTrace();
			}
			return 1;
		})).then(Commands.literal("tps").executes(cmdCtx -> {
			double mspt = Arrays.stream(ASMHookTerminator.lastTickTime)
					.boxed().collect(Collectors.toList()).subList(0, ASMHookTerminator.lastTickTimeFill)
					.stream().mapToDouble(i->i/1000000.0).average().orElse(0);
			Component message = Component.literal(
					"Theoretical peak average TPS is " + 1000/mspt + "tps");
			cmdCtx.getSource().sendSuccess(() -> message, true);
			return 1;
		}));
	}
}
