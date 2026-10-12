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
                var targetMethodName = "tickNonPassenger";
                var targetMethodDesc = "(Lnet/minecraft/world/entity/Entity;)V";

                for (var i in methods) {
                    var method = methods[i];

                    if (!method.name.equals(targetMethodName)) {
                        continue;
                    } else if (!method.desc.equals(targetMethodDesc)) {
                        continue;
                    }
                    asmapi.log("DEBUG", "[JMTSUPERTRANS] Matched method " + method.name + " " + method.desc);

                    var instructions = method.instructions;

                    var callMethod = "tick";
                    var callClass = "net/minecraft/world/entity/Entity";
                    var callDesc = "()V";

                    var callTarget = asmapi.findFirstMethodCallAfter(method, MethodType.VIRTUAL,
                            callClass, callMethod, callDesc, 0);

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
