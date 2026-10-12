function initializeCoreMod() {
    // Disabled on 1.20.1: MinecraftServer's RecursiveEventLoop / canExecute machinery was
    // removed in 1.17+ (replaced by BlockableEventLoop), so the ETP parallel reimplementation
    // has no hook point. This script intentionally does nothing.
    var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    asmapi.log("WARN", "[JMTSUPERTRANS] etpparallel is disabled on 1.20.1 (RecursiveEventLoop removed in 1.17+)");
    return {}
}
