# Build Scripts

## Purpose

Synthesize the Forge 1.12.2-14.23.5.2860 dev toolchain that upstream purged from maven, so ForgeGradle 2.3 can build and run a dev server.

## Ownership

Owns: userdev jar synthesis, merged binpatch generation, new-class injection. Not part of the shipped mod.

## Local Contracts

- `gen-userdev.sh`: builds `forge-<ver>-userdev.jar` + stripped plain `forge-<ver>.jar` into `~/.gradle/caches/minecraft/deobfedDeps/...`. Stash: `~/.cache/mcmt-forge-artifacts/<ver>/`. classes.jar and plain jar must NOT carry `binpatches.pack.lzma` (runtime double-patch is fatal). dev.json must carry Mojang `downloads` + `assetIndex`.
- `GenMergedPatches.java`: universal's `binpatches.pack.lzma` stores `binpatch/{client,server}/`; `TaskApplyBinPatches` needs `binpatch/merged/`. Tool applies client+server patches to their jars, merges with MergeJars.processClass semantics, diffs vs unpatched merged, writes pack200+LZMA. exists=false server patches whose class exists in merged (client-only inner classes) are re-emitted as exists=true patches whose target is the patch-from-empty result (no @SideOnly) — otherwise SideTransformer rejects them on SERVER (Block$1 crash). Interface methods renamed via notch-mcp.srg MD mappings.
- `InjectNewClasses.java`: exists=false patches for classes absent from merged are injected into userdev classes.jar (TaskApplyBinPatches appends classes.jar entries not already present). Interface-method rename required (Item$22 `a`→`apply`).
- Classpath for both tools: FG jar (shaded ASM) + javaxdelta + lzma-java from `~/.gradle/caches/modules-2`; run with JDK 8.
- After regenerating patches: replace `devbinpatches.pack.lzma` inside the userdev jar, then wipe `~/.gradle/caches/forge_gradle` and `.../stable/39` to force re-apply.

## Verification

- `./gradlew runServer` reaches `Done (...)!` with `MCMT coremod transformer active` logged.
- Only tolerated boot noise: `ClientBrandRetriever for invalid side SERVER` (side probe), netty logger NPE (harmless fallback).

## Child DOX Index

None.
