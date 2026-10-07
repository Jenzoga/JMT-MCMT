# src — 1.12.2 Port Tree

## Purpose

The shipped mod source, ported from 1.16.5 to Minecraft 1.12.2 / Forge 14.23.5.2860. Only code that compiles against `stable_39` MCP names and runs on a Forge 1.12 server belongs here.

## Ownership

Owns: mod entry point, coremod harness, ASM hook destination, config, commands. Old 1.16 implementations live in `deprecated/` and are reference material only.

## Local Contracts

- `MCMT.java` + `Constants.java`: mod shell (`@Mod(modid="jmt_mcmt")`), version from gradle.
- `coremod/MCMTLoadingPlugin.java`: FML `IFMLLoadingPlugin`; registers `MCMTClassTransformer`. Dev runs load it via `-Dfml.coreMods.load` (see root AGENTS.md).
- `coremod/MCMTClassTransformer.java`: `IClassTransformer`. 1.13+ JS coremods (`coremods.json`/ASMAPI) do not exist on 1.12; every transformer must be Java against SRG names. Current patches: `MinecraftServer.updateTimeLightAndEntities` (SRG `func_71190_q`) gets `preTick` at entry and each `WorldServer.tick()` site (SRG `func_73028_a`) replaced by `callTick`; `World.updateEntities` (SRG `func_72939_s`) gets each `Entity.onUpdate()` site (SRG `func_70071_h_`) replaced by `callEntityTick`; the same method's `tickableTileEntities` loop gets each `CHECKCAST ITickable` + `INVOKEINTERFACE update()` pair replaced by `callTileEntityTick` (1.12 TEs implement `ITickable` individually — e.g. `TileEntityFurnace` — that loop is the central TE dispatch point). Bytecode names must match both MCP (dev) and SRG (prod) forms.
- `asmdest/ASMHookTerminator.java`: all injected calls terminate here. 1.16 methods intentionally dropped/changed for 1.12: no `postTick` hook (the previous tick is awaited at the next `preTick`); no `sendQueuedBlockEvents` — 1.12 has no queued block-event batch. Entity ticks use `Entity.onUpdate()` (`func_70071_h_`); TE ticks cast back to `ITickable.update()`.

## Work Guidance

- Port subsystem-by-subsystem from `deprecated/java-1.16/`; verify each with `./gradlew compileJava` then a `runServer` smoke test.
- Locate hook targets by scanning `forgeBin-1.12.2-14.23.5.2860.jar` bytecode (javap/ASM), never by translating 1.16 method names.
- Class names/fields referenced by injected bytecode must be SRG-stable (deobfBin maps notch→MCP at build time; injected references are resolved by the runtime remapper — use SRG names in ASM strings where required).

## Verification

- `./gradlew compileJava` green; `./gradlew runServer` boots to `Done (...)!`.

## Child DOX Index

None.
