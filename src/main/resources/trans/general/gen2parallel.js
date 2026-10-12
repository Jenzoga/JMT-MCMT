function initializeCoreMod() {
    return {
    	// ClassInstanceMultiMap replaces ClassInheritanceMultiMap in 1.17+
    	'multiMapCollections': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.util.ClassInstanceMultiMap',
                "methodName": "<init>",
        		"methodDesc": "(Ljava/lang/Class;)V"
            },
            "transformer": function(methodNode) {
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
            }
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
                'type': 'METHOD',
                'class': 'net.minecraft.util.ThreadingDetector',
                "methodName": "checkAndLock",
        		"methodDesc": "()V"
            },
            "transformer": function(methodNode) {
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
            }
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
