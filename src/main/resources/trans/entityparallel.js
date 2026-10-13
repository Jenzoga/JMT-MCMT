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
        'entityTick': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerLevel'
            },
            'transformer': function(classNode) {
                var opcodes = Java.type('org.objectweb.asm.Opcodes');
                var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
                var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
                var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
                var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
                var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
                var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
                var MethodType = asmapi.MethodType;

                asmapi.log("INFO", "[JMTSUPERTRANS] Entity Transformer Called");

                var methods = classNode.methods;

                // 1.20.1: ServerLevel.tickNonPassenger(Entity) is the entity tick entry point
                var targetLdc = ["tickNonPassenger"];
                var targetMethodDesc = "(Lnet/minecraft/world/entity/Entity;)V";

                for (var i in methods) {
                    var method = methods[i];

                    if (method != locateMethod(classNode, targetMethodDesc, targetLdc)) {
                        continue;
                    }
                    asmapi.log("DEBUG", "[JMTSUPERTRANS] Matched method " + method.name + " " + method.desc);

                    var instructions = method.instructions;

                    var callMethod = "tick";
                    var callClass = "net/minecraft/world/entity/Entity";
                    var callDesc = "()V";

                    var callTarget = findCallAfter(method, callClass, callMethod, callDesc, 0,
                            {after: "tickNonPassenger"});

                    if (callTarget == null) {
                        asmapi.log("ERROR", "[JMTSUPERTRANS] MISSING TARGET INSN");
                        return classNode;
                    }

                    // Jump instruction
                    var skipTarget = new LabelNode();

                    var il = new InsnList();
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));   // this (ServerLevel)
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "callEntityTick",
                            "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/server/level/ServerLevel;)V", false));
                    il.add(new JumpInsnNode(opcodes.GOTO, skipTarget));

                    instructions.insertBefore(callTarget, il);
                    instructions.insert(callTarget, skipTarget);

                    asmapi.log("INFO", "[JMTSUPERTRANS] Entity Transformer Complete");

                    break;
                }
                return classNode;
            }
        }
    }
}
