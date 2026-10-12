package org.jmt.mcmt.commands;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.server.level.ServerLevel;

public class DebugCommands {

	public static LiteralArgumentBuilder<CommandSourceStack> registerDebug(LiteralArgumentBuilder<CommandSourceStack> root) {
		return root.then(Commands.literal("getBlockState")
				.then(Commands.argument("location", Vec3Argument.vec3()).executes(cmdCtx -> {
					Vec3 loc = Vec3Argument.getVec3(cmdCtx, "location");
					BlockPos bp = BlockPos.containing(loc);
					ServerLevel sw = cmdCtx.getSource().getLevel();
					BlockState bs = sw.getBlockState(bp);
					Component message = Component.literal(
							"Block at " + bp + " is " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()));
					cmdCtx.getSource().sendSuccess(() -> message, true);
					System.out.println(message.toString());
					return 1;
				}))).then(Commands.literal("nbtdump")
						.then(Commands.argument("location", Vec3Argument.vec3()).executes(cmdCtx -> {
							Vec3 loc = Vec3Argument.getVec3(cmdCtx, "location");
							BlockPos bp = BlockPos.containing(loc);
							ServerLevel sw = cmdCtx.getSource().getLevel();
							BlockState bs = sw.getBlockState(bp);
							BlockEntity te = sw.getBlockEntity(bp);
							if (te == null) {
								Component message = Component.literal(
										"Block at " + bp + " is " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()) + " has no NBT");
								cmdCtx.getSource().sendSuccess(() -> message, true);
							}
							CompoundTag nbt = te.serializeNBT();
							Component itc = Component.literal(nbt.getAsString());
							Component message = Component.literal(
									"Block at " + bp + " is " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()) + " with TE NBT:");
							cmdCtx.getSource().sendSuccess(() -> message, true);
							cmdCtx.getSource().sendSuccess(() -> itc, true);
							//System.out.println(message.toString());
							return 1;
				}))).then(Commands.literal("tick").requires(cmdSrc -> {
					return cmdSrc.hasPermission(2);
				}).then(Commands.literal("te"))
						.then(Commands.argument("location", Vec3Argument.vec3()).executes(cmdCtx -> {
							Vec3 loc = Vec3Argument.getVec3(cmdCtx, "location");
							BlockPos bp = BlockPos.containing(loc);
							ServerLevel sw = cmdCtx.getSource().getLevel();
							BlockEntity te = sw.getBlockEntity(bp);
							if (te != null) {
								// 1.18+ ticks block entities through BlockEntityTicker wrappers;
								// a direct tick call no longer exists, so just report presence.
								Component message = Component.literal(
										te.getClass().getName() + " at " + bp + " ticks via its BlockEntityTicker");
								cmdCtx.getSource().sendSuccess(() -> message, true);
							} else {
								Component message = Component.literal("No TE at " + bp);
								cmdCtx.getSource().sendFailure(message);
							}
							return 1;
						})))
				.then(Commands.literal("classpathDump").requires(cmdSrc -> {
					return cmdSrc.hasPermission(2);
				}).executes(cmdCtx -> {
					Path base = Paths.get("classpath_dump/");
					try {
						Files.createDirectories(base);
					} catch (IOException e1) {
						e1.printStackTrace();
					}
					// Copypasta from syncfu;
					Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).flatMap(path -> {
			        	File file = new File(path);
			        	if (file.isDirectory()) {
			        		return Arrays.stream(file.list((d, n) -> n.endsWith(".jar")));
			        	}
			        	return Arrays.stream(new String[] {path});
			        }).filter(s -> s.endsWith(".jar"))
					.map(Paths::get).forEach(path -> {
			        	Path name = path.getFileName();
			        	try {
							Files.copy(path, Paths.get(base.toString(), name.toString()), StandardCopyOption.REPLACE_EXISTING);
						} catch (IOException e) {
							e.printStackTrace();
						}
					});
					
					
					Component message = Component.literal("Classpath Dumped to: " + base.toAbsolutePath().toString());
					cmdCtx.getSource().sendSuccess(() -> message, true);
					System.out.println(message.toString());
					return 1;
				}))
				/* 1.16.1 code; AKA the only thing that changed  */
				.then(Commands.literal("test").requires(cmdSrc -> {
					return cmdSrc.hasPermission(2);
				}).then(Commands.literal("structures").executes(cmdCtx -> {
					ServerPlayer p = cmdCtx.getSource().getPlayer();
					BlockPos srcpos = p.blockPosition();
					UUID id = p.getUUID();
					int index = structureIdx.computeIfAbsent(id.toString(), (s) -> new AtomicInteger()).getAndIncrement();
					ServerLevel level = cmdCtx.getSource().getLevel();
					net.minecraft.core.Registry<Structure> structureRegistry =
							level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
					List<net.minecraft.resources.ResourceLocation> targets = new ArrayList<>(structureRegistry.keySet());
					if (targets.isEmpty()) {
						cmdCtx.getSource().sendSuccess(() -> Component.literal("No structures registered!"), true);
						return 0;
					}
					net.minecraft.resources.ResourceLocation target = targets.get(Math.abs(index) % targets.size());
					if (index >= targets.size()) {
						structureIdx.computeIfAbsent(id.toString(), (s) -> new AtomicInteger()).set(0);
					}
					ResourceKey<Structure> targetKey = ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE, target);
					Structure targetStructure = structureRegistry.get(target);
					if (targetStructure == null) {
						cmdCtx.getSource().sendSuccess(() -> Component.literal("Failed to resolve structure " + target), true);
						return 0;
					}
					HolderSet<Structure> holderSet = HolderSet.direct(structureRegistry.wrapAsHolder(targetStructure));
					Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
							.findNearestMapStructure(level, holderSet, srcpos, 100, false);
					if (result == null) {
						Component message = Component.literal("Failed locating " + target + " from " + srcpos);
						cmdCtx.getSource().sendSuccess(() -> message, true);
						return 1;
					}
					BlockPos dst = result.getFirst();
					p.connection.teleport((double) dst.getX(), (double) srcpos.getY(), (double) dst.getZ(), p.getXRot(), p.getYRot());
					Component message = Component.literal("Located " + target + " at " + dst.toShortString());
					cmdCtx.getSource().sendSuccess(() -> message, true);
					return 1;
				})))
				/* */
				/*
				.then(Commands.literal("goinf").requires(cmdSrc -> {
					return cmdSrc.hasPermission(2);
				}).executes(cmdCtx -> {
					ServerPlayer p = cmdCtx.getSource().getPlayer();
					p.setPosition(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
					return 1;
				}))
				*/
				;
	}
	
	private static Map<String, AtomicInteger> structureIdx = new ConcurrentHashMap<>();
}
