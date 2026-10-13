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
        'mcserver': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.MinecraftServer'
            },
            'transformer': function(classNode) {
                var opcodes = Java.type('org.objectweb.asm.Opcodes');
                var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
                var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
                var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");
                var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
                var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
                var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
                var LdcInsnNode = Java.type("org.objectweb.asm.tree.LdcInsnNode");
                var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
                var MethodType = asmapi.MethodType;

                asmapi.log("INFO", "[JMTSUPERTRANS] World Transformer Called");
                var methods = classNode.methods;

                // 1.20.1: the per-level tick loop lives in MinecraftServer.tickChildren(BooleanSupplier)
                var targetLdc = ["levels"];
                var targetMethodDesc = "(Ljava/util/function/BooleanSupplier;)V";

                for (var i in methods) {
                    var method = methods[i];

                    if (method != locateMethod(classNode, targetMethodDesc, targetLdc)) {
                        continue;
                    }
                    asmapi.log("DEBUG", "[JMTSUPERTRANS]Matched method " + method.name + " " + method.desc);

                    var instructions = method.instructions;

                    var preTarget = null;
                    var postTarget = null;

                    var arrayLength = instructions.size();
                    for (var i = 0; i < arrayLength; ++i) {
                        var instruction = instructions.get(i);
                        if (instruction instanceof LdcInsnNode) {
                            if (instruction.cst.equals("levels")) {
                                // We have 1 before our pre-insertion point
                                preTarget = instruction;
                                preTarget = preTarget.getNext();
                            } else if (instruction.cst.equals("connection")) {
                                // popPush("connection") sits right after the level loop
                                if (postTarget == null) {
                                    postTarget = instruction;
                                    postTarget = postTarget.getPrevious();
                                    postTarget = postTarget.getPrevious();
                                }
                            }
                        }
                    }

                    var callMethod = "tick";
                    var callClass = "net/minecraft/server/level/ServerLevel";

                    var callTarget = findCallAfter(method, callClass, callMethod, "(Ljava/util/function/BooleanSupplier;)V", 0, null);

                    if (callTarget != null && preTarget != null && postTarget != null) {
                        asmapi.log("INFO", "[JMTSUPERTRANS] FOUND TARGET INSNS");
                    } else {
                        asmapi.log("ERROR", "[JMTSUPERTRANS] MISSING TARGET INSNS:");
                        asmapi.log("ERROR", "[JMTSUPERTRANS] HAVE PRE:" + (preTarget != null));
                        asmapi.log("ERROR", "[JMTSUPERTRANS] HAVE POST:" + (postTarget != null));
                        asmapi.log("ERROR", "[JMTSUPERTRANS] HAVE CALL:" + (callTarget != null));
                        return classNode;
                    }

                    //Call Hook: swap serverlevel.tick(supplier) for
                    //ASMHookTerminator.callTick(serverlevel, supplier, server)
                    var skipTarget = new LabelNode();

                    var il = new InsnList();
                    il.add(new InsnNode(opcodes.POP));            // drop the BooleanSupplier arg
                    il.add(new VarInsnNode(opcodes.ALOAD, 1));   // BooleanSupplier param
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));   // MinecraftServer this
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "callTick",
                            "(Lnet/minecraft/server/level/ServerLevel;Ljava/util/function/BooleanSupplier;Lnet/minecraft/server/MinecraftServer;)V", false));
                    il.add(new JumpInsnNode(opcodes.GOTO, skipTarget));

                    instructions.insertBefore(callTarget, il);
                    instructions.insert(callTarget, skipTarget);

                    //Pre Hook
                    il = new InsnList();
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "preTick",
                            "(Lnet/minecraft/server/MinecraftServer;)V", false));
                    instructions.insert(preTarget, il);

                    //Post Hook
                    il = new InsnList();
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "postTick",
                            "(Lnet/minecraft/server/MinecraftServer;)V", false));
                    instructions.insert(postTarget, il);

                    // FORGE BUS HACKS
                    // callTick posts LevelTickEvent(END) itself; kill Forge's onPostLevelTick
                    // so listeners don't see the event twice. onPreLevelTick (START) is left
                    // in place on the main thread.
                    var skipTarget2 = new LabelNode();
                    var toSkip = findCallAfter(method, "net/minecraftforge/event/ForgeEventFactory", "onPostLevelTick",
                            "(Lnet/minecraft/world/level/Level;Ljava/util/function/BooleanSupplier;)V", 0, null);

                    if (toSkip != null) {
                        il = new InsnList();
                        il.add(new InsnNode(opcodes.POP));
                        il.add(new InsnNode(opcodes.POP));
                        il.add(new JumpInsnNode(opcodes.GOTO, skipTarget2));
                        instructions.insertBefore(toSkip, il);
                        instructions.insert(toSkip, skipTarget2);
                    } else {
                        asmapi.log("WARN", "[JMTSUPERTRANS] onPostLevelTick call not found; event may fire twice");
                    }

                    // Break because this particular coremod only targets one method
                    break;
                }

                asmapi.log("INFO", "[JMTSUPERTRANS] World Transformer Complete");

                return classNode;
            }
        },
    }
}
