# Version history

Mostly here for my own sanity

## 0.24.0-PRE (1.20.1 port)

- Ported to Minecraft 1.20.1 / Forge 47.4.13 (Java 17, ForgeGradle 6, Mojang official mappings)
- ThreadingDetector patch made compatible with ASM 9.8 (Forge 47.4.x), which clears
  removed instruction nodes' links and so broke the mutate-while-scanning loop
- Packaging simplified to a single mod jar; the syncfu launcher jar, nested-jar
  locator and `fullJar` task were removed (nothing in 1.20.1 uses the sync-fu
  fastutil transforms; the concurrent collections are self-synchronising)
- Update checker URL switched from the dead gitcdn.link mirror to
  raw.githubusercontent.com, and `etc/update.json` gained a 1.20.1 section
- All coremods rewritten for the 1.20.1 class layout:
	- World tick loop moved to `MinecraftServer.tickChildren` (was `tickServer`)
	- Entity ticks hook `ServerLevel.tickNonPassenger`; TE ticks hook `LevelChunk$BoundTickingBlockEntity.tick`
	- Environment ticks hook `ServerChunkCache.tickChunks` -> `ServerLevel.tickChunk`
	- `ServerTickList` replaced by `LevelTicks`; `ClassInheritanceMultiMap` by `ClassInstanceMultiMap`;
	  `PalettedContainer` locking by `ThreadingDetector`; entity storage by `EntityLookup`
- AccessTransformer entries switched to intermediary (`f_`/`m_`) names, required by FG6's AT application order
- Disabled hooks (target classes removed or already thread-safe in 1.17+):
	- `RecursiveEventLoop` ETP scheduling (etpparallel)
	- `ServerTickList` self-repair, `RegionSectionCache`, `TicketManager` collections,
	  `TemplateHashMap`, `PlayerRemoveQueue`, `LevelBasedGraph` (merged into ChunkMap)
	- SCP drive debug hooks (folded into the chunk-load drive hook in chunkparallel)

## 0.11.19

- Fixes the postTick event from forge; This was causing issues with SereneSeasons

- A bug has been discovered that apparently breaks some world generation (confirmed against LycanitesMobs)
	- Causes hang on world creation/ingame chunk gen as SCP locks up waiting for completed task to complete (See: SCP.getChunk keyword:isdone)

## 0.11.17

Probably not released

- Changed the entitiesByID retype into a wrapped concurrenthashset
	- This improves mod compatibility, with probably negligable performance impact
	- Lesson to future me: There is no need to be overly aggressive with patching
	- Also foundation of a FastUtil replacement project
- Also changed stats a bit

## 0.10.12

- Fixed bug in entitiesByID patch

## 0.10.11

- Retyped entitiesByID
- Added stats comand

## 0.9.8

First public release