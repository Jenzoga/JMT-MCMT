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

// Same mapping problem one level down: a call site is identified by its owner and
// descriptor, both of which survive renaming, while the member name does not. The
// name is still tried first so development keeps its precise match; if it resolves
// to nothing we fall back to the single callee that matches owner+descriptor. A
// fingerprint ("after": an LDC constant the call must follow) breaks ties.
function findCallAfter(method, owner, name, desc, from, opts) {
	var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
	var LdcInsnNode = Java.type("org.objectweb.asm.tree.LdcInsnNode");
	var afterIdx = -1;
	if (opts != null && opts.after != null) {
		var k = -1;
		for (var m = method.instructions.getFirst(); m != null; m = m.getNext()) {
			k++;
			if (m instanceof LdcInsnNode && m.cst.equals(opts.after)) {
				afterIdx = k;
			}
		}
	}
	var exact = null;
	var shape = null;
	var shapeCount = 0;
	var i = -1;
	for (var n = method.instructions.getFirst(); n != null; n = n.getNext()) {
		i++;
		if (i <= from) {
			continue;
		}
		if (afterIdx >= 0 && i <= afterIdx) {
			continue;
		}
		if (!(n instanceof MethodInsnNode)) {
			continue;
		}
		if (!n.owner.equals(owner) || !n.desc.equals(desc)) {
			continue;
		}
		if (n.name.equals(name)) {
			exact = n;
			break;
		}
		shapeCount++;
		if (shape == null) {
			shape = n;
		}
	}
	if (exact != null) {
		return exact;
	}
	return shapeCount == 1 ? shape : null;
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
        'chunkLoadCache': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerChunkCache'
            },
            'transformer': function(classNode) {
                var opcodes = Java.type('org.objectweb.asm.Opcodes');
                var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
                var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
                var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
                var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
                var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
                var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
                var AbstractInsnNode = Java.type("org.objectweb.asm.tree.AbstractInsnNode");
                var MethodType = asmapi.MethodType;

                asmapi.log("INFO", "[JMTSUPERTRANS] ChunkLoadCache Transformer Called");

                var methods = classNode.methods;

                // 1.20.1: ServerChunkCache.getChunk(int,int,ChunkStatus,boolean)
                var targetLdc = null;
                var targetMethodDesc = "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";

                for (var i in methods) {
                    var method = methods[i];

                    if (method != locateMethod(classNode, targetMethodDesc, targetLdc)) {
                        continue;
                    }
                    asmapi.log("DEBUG", "[JMTSUPERTRANS] Matched method " + method.name + " " + method.desc);

                    var instructions = method.instructions;

                    // Locate the getChunkFutureMainThread call; the future is stored in the
                    // local right after it (ASTORE)
                    var futureCall = findCallAfter(method,
                            "net/minecraft/server/level/ServerChunkCache", "getChunkFutureMainThread",
                            "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;", 0, null);
                    if (futureCall == null) {
                        asmapi.log("ERROR", "[JMTSUPERTRANS] MISSING getChunkFutureMainThread INSN");
                        return classNode;
                    }
                    var futureStore = futureCall.getNext();
                    if (futureStore == null || futureStore.getOpcode() != opcodes.ASTORE) {
                        asmapi.log("ERROR", "[JMTSUPERTRANS] MISSING FUTURE STORE INSN");
                        return classNode;
                    }
                    var futureSlot = futureStore["var"];

                    // The managedBlock call that blocks the thread until the future completes
                    var blockCall = findCallAfter(method,
                            "net/minecraft/server/level/ServerChunkCache$MainThreadExecutor", "managedBlock",
                            "(Ljava/util/function/BooleanSupplier;)V", 0, null);
                    if (blockCall == null) {
                        asmapi.log("ERROR", "[JMTSUPERTRANS] MISSING managedBlock INSN");
                        return classNode;
                    }

                    // if (shouldThreadChunks()) DebugHookTerminator.chunkLoadDrive(scp, future, x, z);
                    var skipTarget = new LabelNode();

                    var il = new InsnList();
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "shouldThreadChunks", "()Z", false));
                    il.add(new JumpInsnNode(opcodes.IFEQ, skipTarget));
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));       // ServerChunkCache this
                    il.add(new VarInsnNode(opcodes.ALOAD, futureSlot)); // completablefuture
                    il.add(new VarInsnNode(opcodes.ILOAD, 1));       // chunk x param
                    il.add(new VarInsnNode(opcodes.ILOAD, 2));       // chunk z param
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/DebugHookTerminator", "chunkLoadDrive",
                            "(Lnet/minecraft/server/level/ServerChunkCache;Ljava/util/concurrent/CompletableFuture;II)V", false));

                    // skipTarget lands on managedBlock itself: when threading is off the
                    // drive block is skipped and vanilla blocking behaviour is preserved.
                    instructions.insertBefore(blockCall, il);
                    instructions.insertBefore(blockCall, skipTarget);

                    asmapi.log("INFO", "[JMTSUPERTRANS] ChunkLoadCache Transformer Complete");

                    break;
                }
                return classNode;
            }
        }
    }
}
