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
                var targetMethodName = "getChunk";
                var targetMethodDesc = "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";

                for (var i in methods) {
                    var method = methods[i];

                    if (!method.name.equals(targetMethodName)) {
                        continue;
                    } else if (!method.desc.equals(targetMethodDesc)) {
                        continue;
                    }
                    asmapi.log("DEBUG", "[JMTSUPERTRANS] Matched method " + method.name + " " + method.desc);

                    var instructions = method.instructions;

                    // Locate the getChunkFutureMainThread call; the future is stored in the
                    // local right after it (ASTORE)
                    var futureCall = asmapi.findFirstMethodCallAfter(method, MethodType.VIRTUAL,
                            "net/minecraft/server/level/ServerChunkCache", "getChunkFutureMainThread",
                            "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;", 0);
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
                    var blockCall = asmapi.findFirstMethodCallAfter(method, MethodType.VIRTUAL,
                            "net/minecraft/server/level/ServerChunkCache$MainThreadExecutor", "managedBlock",
                            "(Ljava/util/function/BooleanSupplier;)V", 0);
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
