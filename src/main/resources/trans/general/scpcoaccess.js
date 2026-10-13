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
    	'UtilExecutorListingHack': {
    		// Track worker threads created by Util.makeExecutor so
    		// ASMHookTerminator.isThreadPooled() recognises them.
    		'target': {
    			'type': 'CLASS',
                'name': 'net.minecraft.Util'
    		},
    		"transformer": function(classNode) {
    			var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
            	var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
            	var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");
            	var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
            	var MethodType = asmapi.MethodType;

            	asmapi.log("INFO", "[JMTSUPERTRANS] UtilExecutorListingHack Transformer Called");

            	var methods = classNode.methods;

            	for (var i in methods) {
            		var methodNode = methods[i];

            		// 1.20.1: the thread factory lambda calls
            		// ForkJoinWorkerThread.setName("Worker-<pool>-<n>")
            		if (!methodNode.desc.endsWith("Lnet/minecraft/util/Util$1;")
            				&& !methodNode.desc.endsWith("Ljava/util/concurrent/ForkJoinWorkerThread;")) {
            			continue;
            		}
            		var target = asmapi.findFirstMethodCallAfter(methodNode, MethodType.VIRTUAL,
            				"java/util/concurrent/ForkJoinWorkerThread", "setName",
            				"(Ljava/lang/String;)V", 0);

            		if (target == null) {
            			continue;
            		}

            		asmapi.log("DEBUG", "[JMTSUPERTRANS] FOUND PICKUP TARGET: " + methodNode.name)

            		// The thread local was stored just before the setName sequence
            		var store = target.getPrevious();
            		while (store != null && store.getOpcode() != opcodes.ASTORE) {
            			store = store.getPrevious();
            		}
            		if (store == null) {
            			asmapi.log("WARN", "[JMTSUPERTRANS] UtilExecutorListingHack: thread local not found");
            			return classNode;
            		}
            		var slot = store["var"];

            		// regThread(thread.getName(), thread)
            		var il = new InsnList();
            		il.add(new VarInsnNode(opcodes.ALOAD, slot));
            		il.add(new InsnNode(opcodes.DUP));
            		il.add(new MethodInsnNode(opcodes.INVOKEVIRTUAL,
            				"java/lang/Thread", "getName", "()Ljava/lang/String;", false));
            		il.add(new InsnNode(opcodes.SWAP));
            		il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
            				"org/jmt/mcmt/asmdest/ASMHookTerminator", "regThread",
            				"(Ljava/lang/String;Ljava/lang/Thread;)V", false));

            		instructions = methodNode.instructions;
            		instructions.insert(target, il);

            		break;
            	}

            	asmapi.log("INFO", "[JMTSUPERTRANS] UtilExecutorListingHack Transformer Complete");

            	return classNode
    		}
    	},
    	'SCPGetChunkNowPatch': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerChunkCache'
            },
            "transformer": classDispatch('(II)Lnet/minecraft/world/level/chunk/LevelChunk;', null, function(methodNode) {
            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
            	var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");

            	asmapi.log("INFO", "[JMTSUPERTRANS] SCPGetChunkNowPatch Transformer Called");

            	// Synchronise: threaded access already returned null before, so
            	// concurrent semantics were "absent chunk" anyway.
            	methodNode.access += opcodes.ACC_SYNCHRONIZED;

            	// Neutralise `if (Thread.currentThread() != this.mainThread) return null;`
            	// by dropping both operands of the reference comparison.
            	var instructions = methodNode.instructions;
            	var target = asmapi.findFirstInstruction(methodNode, opcodes.IF_ACMPNE);

            	if (target == null) {
            		asmapi.log("WARN", "[JMTSUPERTRANS] SCPGetChunkNowPatch: thread check not found");
            		return methodNode;
            	}

            	// Replace the IF_ACMPNE itself with POP POP (drop both operands),
            	// so the stack stays balanced for frame computation.
            	var il = new InsnList();
            	il.add(new InsnNode(opcodes.POP));
            	il.add(new InsnNode(opcodes.POP));
            	instructions.insertBefore(target, il);
            	instructions.remove(target);

            	asmapi.log("INFO", "[JMTSUPERTRANS] SCPGetChunkNowPatch Transformer Complete");

            	return methodNode;
            })
    	},
    	'SCPGetChunkPatch': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerChunkCache'
            },
            "transformer": classDispatch('(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;', null, function(methodNode) {
            	// 1.20.1 getChunkFuture already dispatches off-thread calls onto
            	// mainThreadProcessor via supplyAsync; no thread-check bypass needed.
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	asmapi.log("INFO", "[JMTSUPERTRANS] SCPGetChunkPatch: no-op on 1.20.1");
            	return methodNode;
            })
    	},
    }
}
