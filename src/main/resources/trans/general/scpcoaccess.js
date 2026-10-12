// Functions that make parallel stuff able to load/access world stuff
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
                'type': 'METHOD',
                'class': 'net.minecraft.server.level.ServerChunkCache',
                "methodName": "getChunkNow",
        		"methodDesc": "(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
            },
            "transformer": function(methodNode) {
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
            }
    	},
    	'SCPGetChunkPatch': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.server.level.ServerChunkCache',
                "methodName": "getChunkFuture",
        		"methodDesc": "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"
            },
            "transformer": function(methodNode) {
            	// 1.20.1 getChunkFuture already dispatches off-thread calls onto
            	// mainThreadProcessor via supplyAsync; no thread-check bypass needed.
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	asmapi.log("INFO", "[JMTSUPERTRANS] SCPGetChunkPatch: no-op on 1.20.1");
            	return methodNode;
            }
    	},
    }
}
