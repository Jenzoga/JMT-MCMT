//"net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase"
function initializeCoreMod() {
    return {
    	'OnCollisionPATCH': {
            'target': {
                'type': 'METHOD',
                'class': 'net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase',
                "methodName": "entityInside",
        		"methodDesc": "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V"
            },
            "transformer": function (methodNode) {

            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            	var InsnList = Java.type("org.objectweb.asm.tree.InsnList");
            	var InsnNode = Java.type("org.objectweb.asm.tree.InsnNode");
            	var LabelNode = Java.type("org.objectweb.asm.tree.LabelNode");
            	var JumpInsnNode = Java.type("org.objectweb.asm.tree.JumpInsnNode");
            	var MethodInsnNode = Java.type("org.objectweb.asm.tree.MethodInsnNode");
            	var VarInsnNode = Java.type("org.objectweb.asm.tree.VarInsnNode");
            	var MethodType = asmapi.MethodType;

            	var instructions = methodNode.instructions;

            	asmapi.log("INFO", "[JMTSUPERTRANS] OnCollisionPATCH Transformer Called")

            	var il = new InsnList();

            	var skipTarget = new LabelNode();
            	il.add(new VarInsnNode(opcodes.ALOAD, 0));
            	il.add(new MethodInsnNode(opcodes.INVOKEVIRTUAL,
        				"net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase", "asState",
        				"()Lnet/minecraft/world/level/block/state/BlockState;", false));
            	il.add(new VarInsnNode(opcodes.ALOAD, 1));
            	il.add(new VarInsnNode(opcodes.ALOAD, 2));
            	il.add(new VarInsnNode(opcodes.ALOAD, 3));
        		il.add(new MethodInsnNode(opcodes.INVOKESTATIC,
        				"org/jmt/mcmt/asmdest/PatchHookTerminator", "OnCollisionFix",
        				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)Z", false));
        		il.add(new JumpInsnNode(opcodes.IFEQ, skipTarget));
        		il.add(new InsnNode(opcodes.RETURN));
        		il.add(skipTarget);

        		instructions.insert(il);

            	asmapi.log("INFO", "[JMTSUPERTRANS] OnCollisionPATCH Transformer Complete")

            	return methodNode;
            }
    	},
    }
}
