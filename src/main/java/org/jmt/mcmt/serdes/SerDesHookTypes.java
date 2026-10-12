package org.jmt.mcmt.serdes;


import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

public enum SerDesHookTypes implements ISerDesHookType {
	EntityTick(Entity.class),
	TETick(BlockEntity.class);
	
	Class<?> clazz;
	
	SerDesHookTypes(Class<?> clazz) {
		this.clazz = clazz;
	}

	@Override
	public String getName() {
		return toString();
	}

	@Override
	public Class<?> getSuperclass() {
		return clazz;
	}

	
}
