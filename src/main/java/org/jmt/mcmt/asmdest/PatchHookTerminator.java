package org.jmt.mcmt.asmdest;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class PatchHookTerminator {

	static Set<Class<?>> onCollisionFixBypassClass = new HashSet<>();
	
	static {
		try {
			onCollisionFixBypassClass.add(Class.forName("blusunrize.immersiveengineering.common.blocks.IETileProviderBlock"));
		} catch (ClassNotFoundException cnfe) {}
	}
	
	//TODO Add config for this
	@SuppressWarnings("deprecation")
	public static boolean OnCollisionFix(BlockState bs, Level w, BlockPos p, Entity e) {
		if (onCollisionFixBypassClass.contains(bs.getBlock().getClass())) {
			synchronized (bs.getBlock()) {
				bs.entityInside(w, p, e);
			}
			return true;
		}
		return false;
	}
	
}
