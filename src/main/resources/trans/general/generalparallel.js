// Production Forge 1.20.1 runs with SRG member names, and ASMAPI.mapMethod resolves
// nothing there, so comparing a method's name silently matches nothing. Descriptors
// and the constants/callees a body already references survive renaming, so a method
// is pinned by those instead. A fingerprint that resolves to several methods aborts
// the transform rather than guessing.
function methodFeatures(method) {
	var opcodes = Java.type("org.objectweb.asm.Opcodes");
	var LdcInsnNode = Java.type("org.objectweb.asm.tree.LdcInsnNode");
	var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
	var ldc = {};
	var calls = {};
	for (var n = method.instructions.getFirst(); n != null; n = n.getNext()) {
		if (n instanceof LdcInsnNode && typeof n.cst === "string") {
			ldc[n.cst] = true;
		} else if (n instanceof MethodInsnNode) {
			calls[n.owner + ":" + n.desc] = true;
		}
	}
	return { ldc: ldc, calls: calls };
}

function locateMethod(classNode, desc, fingerprint) {
	var opcodes = Java.type("org.objectweb.asm.Opcodes");
	var wantAll = null;
	var wantNone = null;
	var wantCalls = null;
	if (fingerprint != null) {
		if (fingerprint.length != null) {
			wantAll = fingerprint;
		} else {
			wantAll = fingerprint.all;
			wantNone = fingerprint.none;
			wantCalls = fingerprint.calls;
		}
	}
	var found = [];
	for (var i in classNode.methods) {
		var method = classNode.methods[i];
		if (!method.desc.equals(desc)) {
			continue;
		}
		if ((method.access & (opcodes.ACC_STATIC | opcodes.ACC_SYNTHETIC)) != 0) {
			continue;
		}
		if (wantAll != null || wantNone != null || wantCalls != null) {
			var feats = methodFeatures(method);
			var ok = true;
			for (var a in wantAll) {
				if (!feats.ldc[wantAll[a]]) {
					ok = false;
				}
			}
			for (var b in wantNone) {
				if (feats.ldc[wantNone[b]]) {
					ok = false;
				}
			}
			for (var c in wantCalls) {
				if (!feats.calls[wantCalls[c]]) {
					ok = false;
				}
			}
			if (!ok) {
				continue;
			}
		}
		found.push(method);
	}
	if (found.length > 1) {
		var visible = [];
		for (var j in found) {
			if ((found[j].access & opcodes.ACC_PRIVATE) == 0) {
				visible.push(found[j]);
			}
		}
		if (visible.length == 1) {
			found = visible;
		} else {
			print("[JMTSUPERTRANS] AMBIGUOUS " + classNode.name + " " + desc + " count=" + found.length);
			return null;
		}
	}
	if (found.length != 1) {
		print("[JMTSUPERTRANS] NO MATCH " + classNode.name + " " + desc + " count=" + found.length);
		return null;
	}
	return found[0];
}

function locateField(classNode, desc) {
	var opcodes = Java.type("org.objectweb.asm.Opcodes");
	var found = [];
	for (var i in classNode.fields) {
		var field = classNode.fields[i];
		if (!field.desc.equals(desc)) {
			continue;
		}
		if ((field.access & opcodes.ACC_STATIC) != 0) {
			continue;
		}
		found.push(field);
	}
	if (found.length != 1) {
		print("[JMTSUPERTRANS] " + (found.length == 0 ? "NO FIELD " : "AMBIGUOUS FIELD ") + classNode.name + " " + desc + " count=" + found.length);
		return null;
	}
	return found[0];
}

function classDispatch(desc, fingerprint, transform) {
	return function(classNode) {
		var method = locateMethod(classNode, desc, fingerprint);
		if (method != null) {
			transform(method, classNode);
		}
		return classNode;
	};
}

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
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerChunkCache'
            },
            "transformer": classDispatch('(Ljava/util/function/BooleanSupplier;Z)V', null, synchronizeMethod("SCPTick"))
    	},
    	// LevelTicks replaces ServerTickList in 1.17+
    	'LevelTicksTick': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.ticks.LevelTicks'
            },
            "transformer": classDispatch('(JILjava/util/function/BiConsumer;)V', null, synchronizeMethod("LevelTicksTick"))
    	},
    	'LevelTicksSchedule': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.ticks.LevelTicks'
            },
            "transformer": classDispatch('(Lnet/minecraft/world/ticks/ScheduledTick;)V', null, synchronizeMethod("LevelTicksSchedule"))
    	},
    	'LevelTicksAddContainer': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.ticks.LevelTicks'
            },
            "transformer": classDispatch('(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/ticks/LevelChunkTicks;)V', null, synchronizeMethod("LevelTicksAddContainer"))
    	},
    	'LevelTicksRemoveContainer': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.ticks.LevelTicks'
            },
            "transformer": classDispatch('(Lnet/minecraft/world/level/ChunkPos;)V', null, synchronizeMethod("LevelTicksRemoveContainer"))
    	},
    	// ServerLevel.onBlockStateChange (neighbour updates)
    	'ServerWorldOnBlockStateChange': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerLevel'
            },
            "transformer": classDispatch('(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V', null, synchronizeMethod("ServerWorldOnBlockStateChange"))
    	},
    	// ServerLevel.runBlockEvents - drains the blockEvents queue; guard against
    	// concurrent blockEvent() adds from other world threads
    	'ServerWorldRunBlockEvents': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerLevel'
            },
            "transformer": classDispatch('()V', {calls:["net/minecraft/world/level/BlockEventData:()Lnet/minecraft/core/BlockPos;"]}, synchronizeMethod("ServerWorldRunBlockEvents"))
    	},
    	// ServerLevel.blockEvent - queue for runBlockEvents / runQueuedBlockEvents
    	'ServerWorldBlockEvent': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerLevel'
            },
            "transformer": classDispatch('(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V', null, synchronizeMethod("ServerWorldBlockEvent"))
    	},
    	'ServerWorldClearBlockEvents': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerLevel'
            },
            "transformer": classDispatch('(Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)V', null, synchronizeMethod("ServerWorldClearBlockEvents"))
    	},
    	// PoiManager shared maps
    	'PoiManagerAdd': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.entity.ai.village.poi.PoiManager'
            },
            "transformer": classDispatch('(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)V', null, synchronizeMethod("PoiManagerAdd"))
    	},
    	'PoiManagerRemove': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.entity.ai.village.poi.PoiManager'
            },
            "transformer": classDispatch('(Lnet/minecraft/core/BlockPos;)V', null, synchronizeMethod("PoiManagerRemove"))
    	},
    	'PoiManagerGetInChunk': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.entity.ai.village.poi.PoiManager'
            },
            "transformer": classDispatch('(Ljava/util/function/Predicate;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;', null, synchronizeMethod("PoiManagerGetInChunk"))
    	},
    	'PoiManagerGetType': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.entity.ai.village.poi.PoiManager'
            },
            "transformer": classDispatch('(Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;', null, synchronizeMethod("PoiManagerGetType"))
    	},
    	// Parallel chunk memo, hooked into ServerChunkCache's own lookups.
    	// Deliberately does NOT touch `new ServerChunkCache(...)` in ServerLevel's
    	// constructor: rewriting that instruction hides the operation from any other
    	// mod injecting there (moonlight's fake chunk cache), whose injection check
    	// then fails and crashes the server at startup.
    	// Targets are matched by descriptor only so they resolve under both the
    	// development (official) and production (SRG) member namings.
    	'ParallelChunkCacheHooks': {
    		'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerChunkCache'
            },
            "transformer": function(classNode) {
            	print("[JMTSUPERTRANS] ParallelChunkCacheHooks Transformer Called");

            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
            	var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
            	var FieldNode = Java.type("org.objectweb.asm.tree.FieldNode");
            	var FieldInsnNode = Java.type("org.objectweb.asm.tree.FieldInsnNode");
            	var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
            	var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
            	var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
            	var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");

            	var hookClass = "org/jmt/mcmt/paralelised/ParallelChunkCache";
            	var scp = "Lnet/minecraft/server/level/ServerChunkCache;";
            	var state = "Ljava/lang/Object;";
            	var fieldName = "jmt$chunkCacheState";

            	var specs = [ {
            			desc: "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            			preName: "preGetChunk",
            			preDesc: "(" + state + scp + "IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            			postName: "postGetChunk",
            			postDesc: "(Lnet/minecraft/world/level/chunk/ChunkAccess;" + state + scp + "IILnet/minecraft/world/level/chunk/ChunkStatus;)V",
            			preArgs: [ [opcodes.ALOAD, 0], [opcodes.ILOAD, 1], [opcodes.ILOAD, 2], [opcodes.ALOAD, 3], [opcodes.ILOAD, 4] ],
            			postArgs: [ [opcodes.ALOAD, 0], [opcodes.ILOAD, 1], [opcodes.ILOAD, 2], [opcodes.ALOAD, 3] ]
            	}, {
            			desc: "(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
            			preName: "preGetChunkNow",
            			preDesc: "(" + state + scp + "II)Lnet/minecraft/world/level/chunk/LevelChunk;",
            			postName: "postGetChunkNow",
            			postDesc: "(Lnet/minecraft/world/level/chunk/LevelChunk;" + state + scp + "II)V",
            			preArgs: [ [opcodes.ALOAD, 0], [opcodes.ILOAD, 1], [opcodes.ILOAD, 2] ],
            			postArgs: [ [opcodes.ALOAD, 0], [opcodes.ILOAD, 1], [opcodes.ILOAD, 2] ]
            	} ];

            	var hadField = false;
            	for (var f in classNode.fields) {
            		if (classNode.fields[f].name.equals(fieldName)) {
            			hadField = true;
            		}
            	}
            	if (!hadField) {
            		classNode.fields.add(new FieldNode(opcodes.ACC_PUBLIC, fieldName, state, null, null));
            	}

            	for (var s = 0; s < specs.length; s++) {
            		var spec = specs[s];
            		var method = locateMethod(classNode, spec.desc, null);
            		if (method == null) {
            			continue;
            		}
            		print("[JMTSUPERTRANS] ParallelChunkCacheHooks: hooked " + method.name + " " + spec.desc);

            		var instructions = method.instructions;
            		var first = instructions.getFirst();

            		// Head: if the memo has it, return it; otherwise run vanilla.
            		var earlyLabel = new LabelNode();
            		var headIl = new InsnList();
            		headIl.add(new VarInsnNode(opcodes.ALOAD, 0));
            		headIl.add(new FieldInsnNode(opcodes.GETFIELD, "net/minecraft/server/level/ServerChunkCache", fieldName, state));
            		for (var a = 0; a < spec.preArgs.length; a++) {
            			headIl.add(new VarInsnNode(spec.preArgs[a][0], spec.preArgs[a][1]));
            		}
            		headIl.add(new MethodInsnNode(opcodes.INVOKESTATIC, hookClass, spec.preName, spec.preDesc, false));
            		headIl.add(new InsnNode(opcodes.DUP));
		headIl.add(new JumpInsnNode(opcodes.IFNONNULL, earlyLabel));
            		instructions.insertBefore(first, headIl);

            		// Post: every return path records what vanilla produced.
            		for (var n = instructions.getFirst(); n != null; n = n.getNext()) {
            			if (n.getOpcode() == opcodes.ARETURN) {
            				var postIl = new InsnList();
            				postIl.add(new InsnNode(opcodes.DUP));
            				postIl.add(new VarInsnNode(opcodes.ALOAD, 0));
            				postIl.add(new FieldInsnNode(opcodes.GETFIELD, "net/minecraft/server/level/ServerChunkCache", fieldName, state));
            				for (var b = 0; b < spec.postArgs.length; b++) {
            					postIl.add(new VarInsnNode(spec.postArgs[b][0], spec.postArgs[b][1]));
            				}
            				postIl.add(new MethodInsnNode(opcodes.INVOKESTATIC, hookClass, spec.postName, spec.postDesc, false));
            				instructions.insertBefore(n, postIl);
            			}
            		}

            		instructions.add(earlyLabel);
            		instructions.add(new InsnNode(opcodes.ARETURN));
            		method.maxStack += 8;
            	}

            	print("[JMTSUPERTRANS] ParallelChunkCacheHooks Transformer Complete");
            	return classNode;
            }
    	},

    }
}
