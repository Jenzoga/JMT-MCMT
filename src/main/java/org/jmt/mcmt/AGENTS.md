# src — 1.12.2 Port Tree

## Purpose

The shipped mod source, ported from 1.16.5 to Minecraft 1.12.2 / Forge 14.23.5.2860. Only code that compiles against `stable_39` MCP names and runs on a Forge 1.12 server belongs here.

## Ownership

Owns: mod entry point, coremod harness, ASM hook destination, config, commands. Old 1.16 implementations live in `deprecated/` and are reference material only.

## Local Contracts

- `MCMT.java` + `Constants.java`: mod shell (`@Mod(modid="jmt_mcmt")`), version from gradle.
- `coremod/MCMTLoadingPlugin.java`: FML `IFMLLoadingPlugin`; registers `MCMTClassTransformer`. Dev runs load it via `-Dfml.coreMods.load` (see root AGENTS.md).
- `coremod/MCMTClassTransformer.java`: `IClassTransformer`. 1.13+ JS coremods (`coremods.json`/ASMAPI) do not exist on 1.12; every transformer must be Java against SRG names.
- `asmdest/ASMHookTerminator.java`: single ASM-visible destination for all injected calls; resolves executor factories by reflection so it compiles before hook targets exist.
- Class names/fields referenced by injected bytecode must be SRG-stable (deobfBin maps notch→MCP at build time; injected references are resolved by the runtime remapper — use SRG names in ASM strings where required).

## Work Guidance

- Port subsystem-by-subsystem from `deprecated/java-1.16/`; verify each with `./gradlew compileJava` then a `runServer` smoke test.

## Verification

- `./gradlew compileJava` green; `./gradlew runServer` boots to `Done (...)!`.

## Child DOX Index

None.
