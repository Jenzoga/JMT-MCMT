function initializeCoreMod() {
    return {
        'debugNote': {
            'target': {
                'type': 'METHOD',
                'class': 'net.minecraft.client.gui.components.DebugScreenOverlay',
                "methodName": "getGameInformation",
        		"methodDesc": "()Ljava/util/List;"
            },
            "transformer": function(methodNode) {
            	var opcodes = Java.type('org.objectweb.asm.Opcodes');
            	var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');

				asmapi.log("INFO", "[JMTSUPERTRANS] DebugScreenPatch Transformer Called");

            	var tgtStr = "Integrated server";
            	var newStr = "Integrated multithreaded server";

            	var instructions = methodNode.instructions;

            	var insn = instructions.getFirst();
            	var found = false;
            	while (insn != null) {
            		if (insn.getOpcode() == opcodes.LDC) {
            			var cst = insn.cst;
            			if (typeof cst === 'string' && cst.contains(tgtStr)) {
            				insn.cst = cst.replace(tgtStr, newStr);
            				found = true;
            				break;
            			}
            		}
            		insn = insn.getNext();
            	}

            	if (!found) {
            		asmapi.log("WARN", "[JMTSUPERTRANS] DebugScreenPatch: 'Integrated server' string not found");
            	}

            	asmapi.log("INFO", "[JMTSUPERTRANS] DebugScreenPatch Transformer Complete");

            	return methodNode;
            }
        }
    }
}
