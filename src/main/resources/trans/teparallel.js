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
        'teTick': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity'
            },
            'transformer': function(classNode) {
                var opcodes = Java.type('org.objectweb.asm.Opcodes');
                var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
                var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
                var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
                var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
                var FieldInsnNode = Java.type("org.objectweb.asm.tree.FieldInsnNode");
                var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
                var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
                var MethodType = asmapi.MethodType;

                asmapi.log("INFO", "[JMTSUPERTRANS] TE Transformer Called");

                var methods = classNode.methods;

                // 1.20.1: block entities tick through LevelChunk$BoundTickingBlockEntity.tick()
                var targetLdc = null;
                var targetMethodDesc = "()V";

                for (var i in methods) {
                    var method = methods[i];

                    if (method != locateMethod(classNode, targetMethodDesc, targetLdc)) {
                        continue;
                    }
                    asmapi.log("DEBUG", "[JMTSUPERTRANS] Matched method " + method.name + " " + method.desc);

                    var instructions = method.instructions;

                    // if (ASMHookTerminator.callTileEntityTick(this, this.this$0.level)) return;
                    // (the terminator re-enters tick() on a pool thread with a guard set)
                    var continueLabel = new LabelNode();

                    // The outer LevelChunk link is a synthetic field whose name differs
                    // between namings, so resolve it by descriptor and let the Java-side
                    // hook read .level itself (Java references get remapped, bytecode here does not).
                    var outerLink = locateField(classNode, "Lnet/minecraft/world/level/chunk/LevelChunk;");
                    if (outerLink == null) {
                        asmapi.log("ERROR", "[JMTSUPERTRANS] BoundTickingBlockEntity outer link not found");
                        return classNode;
                    }

                    var il = new InsnList();
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(opcodes.GETFIELD,
                            "net/minecraft/world/level/chunk/LevelChunk$BoundTickingBlockEntity", outerLink.name,
                            "Lnet/minecraft/world/level/chunk/LevelChunk;"));
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "callTileEntityTick",
                            "(Lnet/minecraft/world/level/block/entity/TickingBlockEntity;Lnet/minecraft/world/level/chunk/LevelChunk;)Z", false));
                    il.add(new JumpInsnNode(opcodes.IFEQ, continueLabel));

                    var first = instructions.getFirst();
                    instructions.insertBefore(first, il);
                    instructions.insertBefore(first, continueLabel);

                    break;
                }

                asmapi.log("INFO", "[JMTSUPERTRANS] TE Transformer Complete");

                return classNode;
            }
        }
    }
}
