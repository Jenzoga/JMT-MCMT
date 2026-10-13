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
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('()V', null, function(methodNode) {
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
            })
    	},
    	'EntityLookupGetEntities': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/util/AbortableIterationConsumer;)V', null, synchronizeMethod("EntityLookupGetEntities"))
    	},
    	'EntityLookupGetAllEntities': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('()Ljava/lang/Iterable;', null, synchronizeMethod("EntityLookupGetAllEntities"))
    	},
    	'EntityLookupAdd': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('(Lnet/minecraft/world/level/entity/EntityAccess;)V', {all:["Duplicate entity UUID {}: {}"]}, synchronizeMethod("EntityLookupAdd"))
    	},
    	'EntityLookupRemove': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('(Lnet/minecraft/world/level/entity/EntityAccess;)V', {none:["Duplicate entity UUID {}: {}"]}, synchronizeMethod("EntityLookupRemove"))
    	},
    	'EntityLookupGetEntityById': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('(I)Lnet/minecraft/world/level/entity/EntityAccess;', null, synchronizeMethod("EntityLookupGetEntityById"))
    	},
    	'EntityLookupGetEntityByUuid': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('(Ljava/util/UUID;)Lnet/minecraft/world/level/entity/EntityAccess;', null, synchronizeMethod("EntityLookupGetEntityByUuid"))
    	},
    	'EntityLookupCount': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.entity.EntityLookup'
            },
            "transformer": classDispatch('()I', null, synchronizeMethod("EntityLookupCount"))
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
