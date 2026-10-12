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
                var targetMethodName = "tick";
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

                    // if (ASMHookTerminator.callTileEntityTick(this, this.this$0.level)) return;
                    // (the terminator re-enters tick() on a pool thread with a guard set)
                    var continueLabel = new LabelNode();

                    var il = new InsnList();
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));
                    il.add(new VarInsnNode(opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(opcodes.GETFIELD,
                            "net/minecraft/world/level/chunk/LevelChunk$BoundTickingBlockEntity", "this$0",
                            "Lnet/minecraft/world/level/chunk/LevelChunk;"));
                    il.add(new FieldInsnNode(opcodes.GETFIELD,
                            "net/minecraft/world/level/chunk/LevelChunk", "level",
                            "Lnet/minecraft/world/level/Level;"));
                    il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
                            "org/jmt/mcmt/asmdest/ASMHookTerminator", "callTileEntityTick",
                            "(Lnet/minecraft/world/level/block/entity/TickingBlockEntity;Lnet/minecraft/world/level/Level;)Z", false));
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
