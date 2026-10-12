function synchronizeMethod(debugLine) {
	return function(methodNode) {
		var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');

		asmapi.log("INFO", "[JMTSUPERTRANS] " + debugLine + " Transformer Called");

		var opcodes = Java.type('org.objectweb.asm.Opcodes');

		methodNode.access += opcodes.ACC_SYNCHRONIZED;

		asmapi.log("INFO", "[JMTSUPERTRANS] " + debugLine + " Transformer Complete");

		return methodNode;
	}
}

function toParallelHashSets(methodNode) {
	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
	var MethodType = asmapi.MethodType;
	var instructions = methodNode.instructions;

	var callMethod = "newHashSet";
	var callClass = "com/google/common/collect/Sets";
	var callDesc = "()Ljava/util/HashSet;";

	var tgtMethod = "newHashSet";
	var tgtClass = "org/jmt/mcmt/asmdest/ConcurrentCollections";
	var tgtDesc = "()Ljava/util/Set;";

	var invoke = asmapi.findFirstMethodCallAfter(methodNode, MethodType.STATIC, callClass, callMethod, callDesc, 0);
	if (invoke != null) {
		do {
			asmapi.log("INFO", "[JMTSUPERTRANS] toParallelHashSets Transforming");
			invoke.owner = tgtClass;
			invoke.name = tgtMethod;
			invoke.desc = tgtDesc;
		} while ((invoke = asmapi.findFirstMethodCallAfter(methodNode,
				MethodType.STATIC, callClass, callMethod, callDesc, instructions.indexOf(invoke))) != null)
	}
}

function toParallelHashMaps(methodNode) {
	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
	var MethodType = asmapi.MethodType;
	var instructions = methodNode.instructions;

	var callMethod = "newHashMap";
	var callClass = "com/google/common/collect/Maps";
	var callDesc = "()Ljava/util/HashMap;";

	var tgtMethod = "newHashMap";
	var tgtClass = "org/jmt/mcmt/asmdest/ConcurrentCollections";
	var tgtDesc = "()Ljava/util/Map;";

	var invoke = asmapi.findFirstMethodCallAfter(methodNode, MethodType.STATIC, callClass, callMethod, callDesc, 0);
	if (invoke != null) {
		do {
			asmapi.log("INFO", "[JMTSUPERTRANS] toParallelHashMaps Transforming");
			invoke.owner = tgtClass;
			invoke.name = tgtMethod;
			invoke.desc = tgtDesc;
		} while ((invoke = asmapi.findFirstMethodCallAfter(methodNode,
				MethodType.STATIC, callClass, callMethod, callDesc, instructions.indexOf(invoke))) != null)
	}
}

function toParallelDeque(methodNode) {
	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
	var MethodType = asmapi.MethodType;
	var instructions = methodNode.instructions;

	var callMethod = "newArrayDeque";
	var callClass = "com/google/common/collect/Queues";
	var callDesc = "()Ljava/util/ArrayDeque;";

	var tgtMethod = "newArrayDeque";
	var tgtClass = "org/jmt/mcmt/asmdest/ConcurrentCollections";
	var tgtDesc = "()Ljava/util/Queue;";

	var invoke = asmapi.findFirstMethodCallAfter(methodNode, MethodType.STATIC, callClass, callMethod, callDesc, 0);
	if (invoke != null) {
		do {
			asmapi.log("INFO", "[JMTSUPERTRANS] toParallelDeque Transforming");
			invoke.owner = tgtClass;
			invoke.name = tgtMethod;
			invoke.desc = tgtDesc;
		} while ((invoke = asmapi.findFirstMethodCallAfter(methodNode,
				MethodType.STATIC, callClass, callMethod, callDesc, instructions.indexOf(invoke))) != null)
	}
}


function initializeCoreMod() {
    return {
    	// ServerChunkCache.tick(BooleanSupplier, boolean)
    	'serverChunkProviderTick': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.server.level.ServerChunkCache',
                "methodName": "tick",
        		"methodDesc": "(Ljava/util/function/BooleanSupplier;Z)V"
            },
            "transformer": synchronizeMethod("SCPTick")
    	},
    	// LevelTicks replaces ServerTickList in 1.17+
    	'LevelTicksTick': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.ticks.LevelTicks',
                "methodName": "tick",
        		"methodDesc": "(JILjava/util/function/BiConsumer;)V"
            },
            "transformer": synchronizeMethod("LevelTicksTick")
    	},
    	'LevelTicksSchedule': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.ticks.LevelTicks',
                "methodName": "schedule",
        		"methodDesc": "(Lnet/minecraft/world/ticks/ScheduledTick;)V"
            },
            "transformer": synchronizeMethod("LevelTicksSchedule")
    	},
    	'LevelTicksAddContainer': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.ticks.LevelTicks',
                "methodName": "addContainer",
        		"methodDesc": "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/ticks/LevelChunkTicks;)V"
            },
            "transformer": synchronizeMethod("LevelTicksAddContainer")
    	},
    	'LevelTicksRemoveContainer': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.ticks.LevelTicks',
                "methodName": "removeContainer",
        		"methodDesc": "(Lnet/minecraft/world/level/ChunkPos;)V"
            },
            "transformer": synchronizeMethod("LevelTicksRemoveContainer")
    	},
    	// ServerLevel.onBlockStateChange (neighbour updates)
    	'ServerWorldOnBlockStateChange': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.server.level.ServerLevel',
                "methodName": "onBlockStateChange",
        		"methodDesc": "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V"
            },
            "transformer": synchronizeMethod("ServerWorldOnBlockStateChange")
    	},
    	// ServerLevel.runBlockEvents - drains the blockEvents queue; guard against
    	// concurrent blockEvent() adds from other world threads
    	'ServerWorldRunBlockEvents': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.server.level.ServerLevel',
                "methodName": "runBlockEvents",
        		"methodDesc": "()V"
            },
            "transformer": synchronizeMethod("ServerWorldRunBlockEvents")
    	},
    	// ServerLevel.blockEvent - queue for runBlockEvents / runQueuedBlockEvents
    	'ServerWorldBlockEvent': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.server.level.ServerLevel',
                "methodName": "blockEvent",
        		"methodDesc": "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V"
            },
            "transformer": synchronizeMethod("ServerWorldBlockEvent")
    	},
    	'ServerWorldClearBlockEvents': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.server.level.ServerLevel',
                "methodName": "clearBlockEvents",
        		"methodDesc": "(Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)V"
            },
            "transformer": synchronizeMethod("ServerWorldClearBlockEvents")
    	},
    	// PoiManager shared maps
    	'PoiManagerAdd': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.entity.ai.village.poi.PoiManager',
                "methodName": "add",
        		"methodDesc": "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)V"
            },
            "transformer": synchronizeMethod("PoiManagerAdd")
    	},
    	'PoiManagerRemove': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.entity.ai.village.poi.PoiManager',
                "methodName": "remove",
        		"methodDesc": "(Lnet/minecraft/core/BlockPos;)V"
            },
            "transformer": synchronizeMethod("PoiManagerRemove")
    	},
    	'PoiManagerGetInChunk': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.entity.ai.village.poi.PoiManager',
                "methodName": "getInChunk",
        		"methodDesc": "(Ljava/util/function/Predicate;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;"
            },
            "transformer": synchronizeMethod("PoiManagerGetInChunk")
    	},
    	'PoiManagerGetType': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.world.entity.ai.village.poi.PoiManager',
                "methodName": "getType",
        		"methodDesc": "(Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;"
            },
            "transformer": synchronizeMethod("PoiManagerGetType")
    	},
    	// Swap vanilla ServerChunkCache for our parallel implementation
    	'ServerWorldParaProvider': {
    		'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerLevel'
            },
            "transformer": function(classNode) {
            	print("[JMTSUPERTRANS] ServerWorldParaProvider Transformer Called");

            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var MethodType = asmapi.MethodType;

            	var callClass = "net/minecraft/server/level/ServerChunkCache";
            	var callDesc = "(Lnet/minecraft/server/level/ServerLevel;"
            			+ "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
            			+ "Lcom/mojang/datafixers/DataFixer;"
            			+ "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;"
            			+ "Ljava/util/concurrent/Executor;"
            			+ "Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            			+ "IIZ"
            			+ "Lnet/minecraft/server/level/progress/ChunkProgressListener;"
            			+ "Lnet/minecraft/world/level/entity/ChunkStatusUpdateListener;"
            			+ "Ljava/util/function/Supplier;)V";

            	var methods = classNode.methods;

            	for (var i in methods) {
            		var method = methods[i];

            		var callTarget = asmapi.findFirstMethodCallAfter(method, MethodType.SPECIAL,
            				callClass, "<init>", callDesc, 0);

            		if (callTarget == null) {
            			continue;
            		}

            		print("[JMTSUPERTRANS] Matched method " + method.name + " " + method.desc);

            		var tgtClass = "org/jmt/mcmt/paralelised/ParaServerChunkProvider";

            		var newTgt = callTarget;
            		while (newTgt != null && newTgt.getOpcode() != opcodes.NEW) {
            			newTgt = newTgt.getPrevious();
            		}

            		if (newTgt == null) {
            			print("[JMTSUPERTRANS] MISSING NEW INSN");
            			return classNode;
            		}

            		callTarget.owner = tgtClass;

            		newTgt.desc = tgtClass;
            	}

            	print("[JMTSUPERTRANS] ServerWorldParaProvider Transformer Complete");

            	return classNode;
            }
    	}
    	/*
    	 * Removed on 1.20.1 (nothing left to transform):
    	 *  - ServerWorldCollections*: ServerLevel ctor no longer initialises Sets/Queues
    	 *    collections (block-event queue handled by ASMHookTerminator instead)
    	 *  - PlayerEntityRemoveQueueSync: ServerPlayer removeQueue removed in 1.19+
    	 *  - TemplateManagerHashMap: StructureTemplateManager already uses newConcurrentMap
    	 *  - TicketManagerCollections: TicketManager merged into ChunkMap in 1.17+
    	 *  - ThreadTaskExecutorCollections: BlockableEventLoop already uses
    	 *    Queues.newConcurrentLinkedQueue for pendingRunnables
    	 */
    }
}
