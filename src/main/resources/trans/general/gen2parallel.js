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

function initializeCoreMod() {
    return {
    	// ClassInstanceMultiMap replaces ClassInheritanceMultiMap in 1.17+
    	'multiMapCollections': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.util.ClassInstanceMultiMap'
            },
            "transformer": classDispatch('(Ljava/lang/Class;)V', null, function(methodNode) {
            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var MethodType = asmapi.MethodType;
            	var instructions = methodNode.instructions;

            	asmapi.log("INFO", "[JMTSUPERTRANS] MultiMap Collections Transformer Called");

            	// Maps.newHashMap()Ljava/util/HashMap; -> ConcurrentCollections.newHashMap()Ljava/util/Map;
            	var invoke = asmapi.findFirstMethodCallAfter(methodNode, MethodType.STATIC,
            			"com/google/common/collect/Maps", "newHashMap", "()Ljava/util/HashMap;", 0);
            	if (invoke != null) {
            		invoke.owner = "org/jmt/mcmt/asmdest/ConcurrentCollections";
            		invoke.desc = "()Ljava/util/Map;";
            	}

            	// Lists.newArrayList()Ljava/util/ArrayList; -> ConcurrentCollections.newLinkedList()Ljava/util/List;
            	var listInvoke = asmapi.findFirstMethodCallAfter(methodNode, MethodType.STATIC,
            			"com/google/common/collect/Lists", "newArrayList", "()Ljava/util/ArrayList;", 0);
            	if (listInvoke != null) {
            		listInvoke.owner = "org/jmt/mcmt/asmdest/ConcurrentCollections";
            		listInvoke.name = "newLinkedList";
            		listInvoke.desc = "()Ljava/util/List;";
            	}

            	asmapi.log("INFO", "[JMTSUPERTRANS] MultiMap Collections Transformer Complete");

            	return methodNode;
            })
    	},
    	// ClassInstanceMultiMap.find(Class) collector (lives in the lambda body,
    	// so scan every method of the class)
    	'classInstanceMultiMapGBC': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.util.ClassInstanceMultiMap'
            },
            "transformer": function(classNode) {
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var MethodType = asmapi.MethodType;

            	asmapi.log("INFO", "[JMTSUPERTRANS] ClassInstanceMultiMapGBC Transformer Called");

            	var methods = classNode.methods;
            	for (var i in methods) {
            		var method = methods[i];
            		var callTarget = asmapi.findFirstMethodCallAfter(method, MethodType.STATIC,
            				"java/util/stream/Collectors", "toList", "()Ljava/util/stream/Collector;", 0);
            		while (callTarget != null) {
            			callTarget.owner = "org/jmt/mcmt/asmdest/ConcurrentCollections";
            			callTarget.name = "toList";
            			callTarget.desc = "()Ljava/util/stream/Collector;";
            			callTarget = asmapi.findFirstMethodCallAfter(method, MethodType.STATIC,
            					"java/util/stream/Collectors", "toList", "()Ljava/util/stream/Collector;", 0);
            			// re-find skips nothing: we just rewrote owner/desc so it no longer matches
            			break;
            		}
            	}

            	asmapi.log("INFO", "[JMTSUPERTRANS] ClassInstanceMultiMapGBC Transformer Complete");

            	return classNode;
            }
    	},
    	// ThreadingDetector replaces the PalettedContainer thread lock in 1.17+.
    	// Neutralise the multi-thread detection (skip storing the offending thread and
    	// never throw), while keeping the semaphore-based mutual exclusion.
    	'threadingDetectorNeutralise': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.util.ThreadingDetector'
            },
            "transformer": classDispatch('()V', {calls:["java/util/concurrent/Semaphore:()Z"]}, function(methodNode) {
            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
            	var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");
            	var FieldInsnNode = Java.type("org.objectweb.asm.tree.FieldInsnNode");

            	asmapi.log("INFO", "[JMTSUPERTRANS] ThreadingDetector Transformer Called");

            	var instructions = methodNode.instructions;

            	// Two-pass: locate targets first, then patch. ASM 9.7+ clears a
            	// removed node's links, so mutating while iterating would stop the scan.
            	var storeNode = null;
            	var throwNode = null;
            	var insn = instructions.getFirst();
            	while (insn != null) {
            		if (storeNode == null && insn instanceof FieldInsnNode
            				&& insn.getOpcode() == opcodes.PUTFIELD
            				&& insn.name.equals("threadThatFailedToAcquire")) {
            			storeNode = insn;
            		} else if (throwNode == null && insn.getOpcode() == opcodes.ATHROW) {
            			throwNode = insn;
            		}
            		insn = insn.getNext();
            	}

            	var patchedStore = storeNode != null;
            	var patchedThrow = throwNode != null;

            	if (storeNode != null) {
            		// Stack here: [this][thread]. Replace the PUTFIELD with POP POP
            		// so both operands are dropped and the stack stays balanced.
            		var il = new InsnList();
            		il.add(new InsnNode(opcodes.POP));
            		il.add(new InsnNode(opcodes.POP));
            		instructions.insertBefore(storeNode, il);
            		instructions.remove(storeNode);
            	}
            	if (throwNode != null) {
            		// The fullException throw. Replace it with POP so we fall through
            		// to the finally block instead of throwing.
            		var il2 = new InsnList();
            		il2.add(new InsnNode(opcodes.POP));
            		instructions.insertBefore(throwNode, il2);
            		instructions.remove(throwNode);
            	}

            	if (!patchedStore || !patchedThrow) {
            		asmapi.log("WARN", "[JMTSUPERTRANS] ThreadingDetector partial patch: store=" + patchedStore + " throw=" + patchedThrow);
            	}

            	asmapi.log("INFO", "[JMTSUPERTRANS] ThreadingDetector Transformer Complete");

            	return methodNode;
            })
    	}
    	/*
    	 * Removed on 1.20.1:
    	 *  - ServerTickListSelfRepair: ServerTickList removed (LevelTicks); the fixSTL
    	 *    terminator path no longer exists
    	 *  - WorldGetTE / WorldGetTECraftBukkit: targeted CraftBukkit-only getTileEntity
    	 *    shapes that no longer exist
    	 */
    }
}
