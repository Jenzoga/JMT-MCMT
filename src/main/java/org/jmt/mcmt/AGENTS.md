# src — 1.12.2 Port Tree

## Purpose

The shipped mod source, ported from 1.16.5 to Minecraft 1.12.2 / Forge 14.23.5.2860. Only code that compiles against `stable_39` MCP names and runs on a Forge 1.12 server belongs here.

## Ownership

Owns: mod entry point, coremod harness, ASM hook destination, config, commands. Old 1.16 implementations live in `deprecated/` and are reference material only.

## Local Contracts

- `MCMT.java` + `Constants.java`: mod shell (`@Mod(modid="jmt_mcmt")`), version from gradle.
- `coremod/MCMTLoadingPlugin.java`: FML `IFMLLoadingPlugin`; registers `MCMTClassTransformer`. Dev runs load it via `-Dfml.coreMods.load` (see root AGENTS.md).
- `coremod/MCMTClassTransformer.java`: `IClassTransformer`. 1.13+ JS coremods (`coremods.json`/ASMAPI) do not exist on 1.12; every transformer must be Java against SRG names. Current patches: `MinecraftServer.updateTimeLightAndEntities` (SRG `func_71190_q`) gets `preTick` at entry and each `WorldServer.tick()` site (SRG `func_73028_a`) replaced by `callTick`; `World.updateEntities` (SRG `func_72939_s`) gets each `Entity.onUpdate()` site (SRG `func_70071_h_`) replaced by `callEntityTick`; the same method's `tickableTileEntities` loop gets each `CHECKCAST ITickable` + `INVOKEINTERFACE update()` pair replaced by `callTileEntityTick` (1.12 TEs implement `ITickable` individually — e.g. `TileEntityFurnace` — that loop is the central TE dispatch point). `WorldServer.updateBlocks` clones its per-chunk loop body via ASM into synthetic `mcmt$tickEnvChunk(Chunk,IZZ)V` (locals remapped, profiler redirected to no-ops, COMPUTE_FRAMES writer); a `shouldDispatchEnv()` guard routes to the clone through `callEnvTick`/`ChunkLock` only when `parallelEnv=true`, keeping the original body (and any other coremod's ordinal-based insertions, e.g. RLTweaker) live in place otherwise. `scheduleBlockUpdate`/`updateBlockTick`/`isBlockTickPending`/`isUpdateScheduled`/`tickUpdates`/`getPendingBlockUpdates` get synchronized wrappers (rename original to `<name>$mcmt$safe`, wrapper body = `synchronized(pendingTickListEntriesTreeSet)`; no new locals so COMPUTE_FRAMES stays correct) because pool-thread entity/TE ticks otherwise corrupt the HashSet/TreeSet size invariant ("TickNextTick list out of synch"). `World` + `WorldServer` entity entry points (`spawnEntity`/`removeEntity`/`onEntityRemoved`/`removeEntityDangerously`/`onEntityAdded` + `WorldServer.updateEntities` + `World.unloadEntities`) get the same wrapper pattern on `synchronized(loadedEntityList)` (`field_72996_f`); `EntityTracker.tick`/`track`/`untrack` get wrapped on its private `entries` Set — private fields are unmapped in prod so resolve them by descriptor (`Ljava/util/Set;`), lock order `loadedEntityList → entries`; `updateEntities` additionally nests `synchronized(unloadedEntityList)` (`field_72997_g`) because both vanilla's removal block and foamfix's hoisted `removeUnloadedEntities` iterate that list while pool-thread chunk loads add to it via `Chunk.setChunkDataFromThis` → `World.unloadEntities`. Global lock order: loaded → unloaded (no thread takes them reversed). WorldServer overrides must be wrapped too or virtual dispatch bypasses the World wrappers; renaming `updateEntities` also neutralizes foamfix's `WorldServerRemovalPatch` (its injection anchor, a SIPUSH 300 + IFNULL pair, is absent from the wrapper body). Inherited fields resolve via naming-mode detection (`func_`/`field_` prefix presence). Wrapper delegates use INVOKESPECIAL on `cn.name` — INVOKEVIRTUAL on renamed originals that subclasses super() into causes infinite wrapper recursion. `patchWorld` writes with COMPUTE_FRAMES (wrapper handler entries need stackmap frames). Bytecode names must match both MCP (dev) and SRG (prod) forms; verified SRG names: `tick`=func_72835_b, `updateBlocks`=func_147456_g, `ITickable.update`=func_73660_a, `Profiler.startSection/endStartSection`=func_76320_a/func_76318_c — consult `mcp-srg.srg`, never guess.
- `commands/CommandMCMT.java`: `/mcmt stats` reports pool size, in-flight world/entity/TE/env counters, and rolling average tick time (recorded at the `preTick` await).
- `asmdest/ASMHookTerminator.java`: all injected calls terminate here. `populateCrashReport()` is embedded into server crash reports via the `DedicatedServer.addServerInfoToCrashReport` patch. 1.16 methods intentionally dropped/changed for 1.12: no `postTick` hook (the previous tick is awaited at the next `preTick`); no `sendQueuedBlockEvents` — 1.12 has no queued block-event batch. Entity ticks use `Entity.onUpdate()` (`func_70071_h_`); TE ticks cast back to `ITickable.update()`; env chunk ticks go through a cached `MethodHandle` to `mcmt$tickEnvChunk` under `paralelised/ChunkLock` (ReentrantLock map keyed by packed chunk pos).

## Work Guidance

- Port subsystem-by-subsystem from `deprecated/java-1.16/`; verify each with `./gradlew compileJava` then a `runServer` smoke test.
- Locate hook targets by scanning `forgeBin-1.12.2-14.23.5.2860.jar` bytecode (javap/ASM), never by translating 1.16 method names.
- Class names/fields referenced by injected bytecode must be SRG-stable (deobfBin maps notch→MCP at build time; injected references are resolved by the runtime remapper — use SRG names in ASM strings where required).

## Verification

- `./gradlew compileJava` green; `./gradlew runServer` boots to `Done (...)!` (dev server needs a free port — set `run/server.properties` `server-port` if 25565 is taken).
- Production artifacts must come from `./gradlew reobfJar` (output `build/libs/jmt_mcmt-*.jar`); a plain `jar` ships MCP names and crashes with `AbstractMethodError` in production. `runServer` also rebuilds that jar with MCP names and silently overwrites it, and a later plain `reobfJar` can report success without rewriting — after any `runServer`, ship only via `./gradlew reobfJar --rerun-tasks` and confirm with `javap -p` on `CommandMCMT` (methods must read `func_71517_b`/`func_71518_a`).
- Prod-class-state check: `/tmp/prodtest` harness (forge installer offline install + dep classpath from FG caches, `FMLServerTweaker` launch) runs the reobfuscated jar against real SRG/obfuscated classes; verify all patch lines appear and a fresh-world 10-minute soak is exception-free. Debug tick-list drift with `-Dmcmt.debug=true`.

## Child DOX Index

None.
