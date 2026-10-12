function initializeCoreMod() {
    return {
        'ServerChunkCacheTickChunks': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.level.ServerChunkCache'
            },
            "transformer": function(classNode) {
                var opcodes = Java.type('org.objectweb.asm.Opcodes');
                var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
                var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
                var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
                var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
                var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
                var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
                var MethodType = asmapi.MethodType;

                asmapi.log("INFO", "[JMTSUPERTRANS] ServerChunkCacheTickChunks Transformer Called");

                var methods = classNode.methods;

                // 1.20.1: ServerChunkCache.tickChunks() drives ServerLevel.tickChunk(chunk, k)
                var targetMethodName = "tickChunks";
                var targetMethodDesc = "()V";

                for (var i in methods) {
                    var method = methods[i];

                    if (!method.name.equals(targetMethodName)) {
                        continue;
                    } else if (!method.desc.equals(targetMethodDesc)) {
                        continue;
                    }

                    asmapi.log("DEBUG", "[JMTSUPERTRANS] Matched method " + method.name + " " + method.desc);

                    var instructions = method.instructions;

                    var callMethod = "tickChunk";
                    var callClass = "net/minecraft/server/level/ServerLevel";
                    var callDesc = "(Lnet/minecraft/world/level/chunk/LevelChunk;I)V";

                    var callTarget = asmapi.findFirstMethodCallAfter(method, MethodType.VIRTUAL,
                            callClass, callMethod, callDesc, 0);

                    if (callTarget == null) {
                        asmapi.log("ERROR", "[JMTSUPERTRANS] MISSING TARGET INSN; dumping calls:");
                        var dump = instructions.getFirst();
                        var count = 0;
                        while (dump != null && count < 1500) {
                            if (dump instanceof MethodInsnNode) {
                                asmapi.log("ERROR", "CALL|" + dump.opcode + "|" + dump.owner + "|" + dump.name + "|" + dump.desc);
                            }
                            dump = dump.getNext();
                            count++;
                        }
                        return classNode;
                    }

                    // Jump instruction
                    var skipTarget = new LabelNode();

                    var il = new InsnList();
                    il.add(new VarInsnNode(opcodes.ALOAD, 0)); // this (ServerChunkCache)
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "callTickEnvironment",
                            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;ILnet/minecraft/server/level/ServerChunkCache;)V", false));
                    il.add(new JumpInsnNode(opcodes.GOTO, skipTarget));

                    instructions.insertBefore(callTarget, il);
                    instructions.insert(callTarget, skipTarget);

                    break;
                }

                asmapi.log("INFO", "[JMTSUPERTRANS] ServerChunkCacheTickChunks Transformer Complete");

                return classNode;
            }
        }
    }
}
