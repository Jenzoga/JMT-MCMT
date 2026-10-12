function printInsnNode(printTgt) {
	print(printTgt+"|"+printTgt.opcode
			+"|"+printTgt.desc+"|"+printTgt.owner+"|"+printTgt.name+"|"+printTgt["var"])
}

function synchronizeMethod(debugLine) {
	return function(methodNode) {
		var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
		var opcodes = Java.type('org.objectweb.asm.Opcodes');
		asmapi.log("INFO", "[JMTSUPERTRANS] " + debugLine + " Transformer Called");
		methodNode.access += opcodes.ACC_SYNCHRONIZED;
		asmapi.log("INFO", "[JMTSUPERTRANS] " + debugLine + " Transformer Complete");
		return methodNode;
	}
}

function initializeCoreMod() {
    return {
    	// EntityLookup replaces ServerWorld.entitiesById in 1.18+.
    	// Swap the fastutil maps for our concurrent equivalents.
    	'EntityLookupMaps': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "<init>",
        		"methodDesc": "()V"
            },
            "transformer": function(methodNode) {
            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
            	var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");
            	var TypeInsnNode = Java.type("org.objectweb.asm.tree.TypeInsnNode");
            	var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
            	var MethodType = asmapi.MethodType;

            	asmapi.log("INFO", "[JMTSUPERTRANS] EntityLookupMaps Transformer Called");

            	// byId: Int2ObjectLinkedOpenHashMap -> Int2ObjectConcurrentHashMap
            	var initTgt = asmapi.findFirstMethodCall(methodNode, MethodType.SPECIAL,
            			"it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap", "<init>", "()V");
            	if (initTgt != null) {
            		var il = new InsnList();
            		il.add(new InsnNode(opcodes.POP));
            		il.add(new TypeInsnNode(opcodes.NEW,
            				"org/jmt/mcmt/paralelised/fastutil/Int2ObjectConcurrentHashMap"));
            		il.add(new InsnNode(opcodes.DUP));
            		il.add(new MethodInsnNode(opcodes.INVOKESPECIAL,
            				"org/jmt/mcmt/paralelised/fastutil/Int2ObjectConcurrentHashMap",
            				"<init>", "()V", false));
            		instructions = methodNode.instructions;
            		instructions.insert(initTgt, il);
            	} else {
            		asmapi.log("WARN", "[JMTSUPERTRANS] EntityLookupMaps: byId init not found");
            	}

            	// byUuid: Maps.newHashMap -> ConcurrentCollections.newHashMap
            	var mapInvoke = asmapi.findFirstMethodCallAfter(methodNode, MethodType.STATIC,
            			"com/google/common/collect/Maps", "newHashMap", "()Ljava/util/HashMap;", 0);
            	if (mapInvoke != null) {
            		mapInvoke.owner = "org/jmt/mcmt/asmdest/ConcurrentCollections";
            		mapInvoke.desc = "()Ljava/util/Map;";
            	}

            	asmapi.log("INFO", "[JMTSUPERTRANS] EntityLookupMaps Transformer Complete");

            	return methodNode;
            }
    	},
    	'EntityLookupGetEntities': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "getEntities",
        		"methodDesc": "(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/util/AbortableIterationConsumer;)V"
            },
            "transformer": synchronizeMethod("EntityLookupGetEntities")
    	},
    	'EntityLookupGetAllEntities': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "getAllEntities",
        		"methodDesc": "()Ljava/lang/Iterable;"
            },
            "transformer": synchronizeMethod("EntityLookupGetAllEntities")
    	},
    	'EntityLookupAdd': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "add",
        		"methodDesc": "(Lnet/minecraft/world/level/entity/EntityAccess;)V"
            },
            "transformer": synchronizeMethod("EntityLookupAdd")
    	},
    	'EntityLookupRemove': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "remove",
        		"methodDesc": "(Lnet/minecraft/world/level/entity/EntityAccess;)V"
            },
            "transformer": synchronizeMethod("EntityLookupRemove")
    	},
    	'EntityLookupGetEntityById': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "getEntity",
        		"methodDesc": "(I)Lnet/minecraft/world/level/entity/EntityAccess;"
            },
            "transformer": synchronizeMethod("EntityLookupGetEntityById")
    	},
    	'EntityLookupGetEntityByUuid': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "getEntity",
        		"methodDesc": "(Ljava/util/UUID;)Lnet/minecraft/world/level/entity/EntityAccess;"
            },
            "transformer": synchronizeMethod("EntityLookupGetEntityByUuid")
    	},
    	'EntityLookupCount': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.level.entity.EntityLookup',
                "methodName": "count",
        		"methodDesc": "()I"
            },
            "transformer": synchronizeMethod("EntityLookupCount")
    	}
    	/*
    	 * Removed on 1.20.1:
    	 *  - ServerWorldBlockPosFastUtkill: blockEvents queue is now handled by
    	 *    ASMHookTerminator.runQueuedBlockEvents + synchronisation in generalparallel
    	 *  - ServerWorldEntitiesByIdFastUtkill: entitiesById moved into EntityLookup
    	 *    (handled by EntityLookupMaps above)
    	 *  - LevelBasedGraph-*: LevelBasedGraph removed (lighting rewrite)
    	 *  - RegionSectionCache-*: RegionSectionCache removed in 1.17+
    	 *  - TicketManagerTickets: TicketManager merged into ChunkMap in 1.17+
    	 */
    }
}
