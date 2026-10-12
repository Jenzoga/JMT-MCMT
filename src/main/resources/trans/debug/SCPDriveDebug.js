function initializeCoreMod() {
    // 1.20.1 port notes:
    //  - The chunk-load drive hook (DebugHookTerminator.chunkLoadDrive around the
    //    mainThreadProcessor.managedBlock wait in ServerChunkCache.getChunk) now lives
    //    in trans/chunkparallel.js, which targets the same call site.
    //  - MinecraftServer's startup "wait for initial chunks" loop keyed off
    //    getLoadedChunksCount() no longer exists in 1.20.1, so InitialChunkCountBypass
    //    has no hook point.
    var asmapi = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    asmapi.log("INFO", "[JMTSUPERTRANS] SCPDriveDebug: hooks moved to chunkparallel / removed on 1.20.1");
    return {}
}
