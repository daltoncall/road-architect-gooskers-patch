# Beta-1.3.21 — Release audit and verification

This is a consolidated record of the Beta-1.3.21 safety audit and build verification. These checks do **not** constitute an in-game modpack playtest.

## Safety audit

# Road Architect Beta-1.3.21 safety audit

Beta-1.3.21 is the normal playtest build after isolating the Distant Horizons failure to Chunky/DH 3.3.2.

Kept from 0.3.19:
- loaded-only chunk readiness (`getChunkNow`) in post-load road/tree queues
- bounded/fair queue rotation
- no new 256-column height snapshot generation from path queries
- one-centerline-point atomic write budget
- seven-block tree cleanup and persisted exact-block-id removal
- generated-structure/player-placement protection
- unsupported/floating road surface rejection

Reverted from the false DH lead:
- 0.3.20 diagnostic no-runtime-hooks behavior
- 0.3.18 seven-tick road-write delay
- 0.3.19 live LevelChunk height preference in PathFinder

The Gradle distribution is unavailable in the sandbox, so the release JAR is produced by a targeted bytecode patch of the verified 0.3.19 JAR and then inspected with javap/archive tests.

## Build verification

Road Architect: Goosker's Patch Beta-1.3.21 verification

Verified release JAR archive integrity with unzip -t.
Verified Fabric metadata reports Beta-1.3.21.
Verified RoadPipelineFabricEvents.register() registers CHUNK_LOAD, START_SERVER_TICK, and SERVER_STOPPING callbacks.
Verified GooskerRoadBackfill processOne() uses modulo 1 (one bounded centerline point may run each server tick), removing the 0.3.18 seven-tick diagnostic throttle.
Verified PathFinder.sampleHeight() uses CacheManager.getHeight(world,key,IntSupplier) natural generator sampling rather than CacheManager.getHeight(world,x,z) live LevelChunk height.
Verified CacheManager.ensureChunkSnapshot() remains lookup-only and does not create a 256-column snapshot on a path height miss.
Verified the only runtime class differences from 0.3.19 are GooskerRoadBackfill and PathFinder.
Verified TreeRemovalQueue, CompleteTreeRemoval, TreeBuildProtection, PlayerTreePlacementMixin, RoadStructureProtection, RoadFeature, RoadPipelineController, and CacheManager bytecode are unchanged from 0.3.19.

Gradle could not be executed in this sandbox because the Gradle 9.1.0 distribution is not cached and outbound binary download is unavailable. Full in-game behavior still requires playtesting.
