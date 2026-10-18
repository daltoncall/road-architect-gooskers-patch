# 0.3.14 Post-generation Stability Audit

This pass intentionally adds no new road-generation features. It audits failure modes that can freeze or terminate an integrated/dedicated server during structure discovery, path planning, loaded-chunk backfill, optional compatibility, and cache persistence.

## Enforced invariants

- **Never wait for a chunk from a chunk-load callback.** Entrance detection uses `ServerChunkCache#getChunkNow` and direct `LevelChunk#getBlockState`; incomplete footprints are deferred.
- **Never block on duplicate snapshot work.** `CacheManager.ensureChunkSnapshot` uses non-blocking `getNow(null)` for an already-running computation.
- **Never submit CPU planning merely to wait for it.** Structure candidate planning is sequential and capped at 8,192 candidates.
- **Never let an optional/API linkage mismatch escape the pipeline.** `PipelineRunner` catches `LinkageError` alongside ordinary exceptions. Fatal VM errors are not blanket-caught.
- **Never let malformed parent data loop forever.** Path reconstruction has cycle, missing-parent, and hop-budget guards.
- **Never force-load chunks for loaded-world backfill.** Backfill requires `getChunkNow` for a bounded road-width-aware chunk margin before modifying terrain.
- **Never crash class initialization when internal backfill hooks change.** Reflection failure disables that backfill path safely.
- **Bound background pressure.** Automatic Road Architect async parallelism is capped at eight workers.
- **Serialize persistent region writes per store.** This reduces concurrent temp-file/atomic-move conflicts.

## Static checks performed for the release JAR

The release audit checks that the patched runtime bytecode contains `CompletableFuture#getNow`, `Stream#limit(8192)`, a `LinkageError` exception-table handler, `Math.min(..., 8)` for automatic async parallelism, guarded path reconstruction, `ServerChunkCache#getChunkNow` in backfill readiness checks, and `ACC_SYNCHRONIZED` on region flushes. It also verifies the JAR archive and metadata/version synchronization.

This hardening significantly reduces the known hard-hang/crash vectors, but it is not a guarantee that an alpha world-generation mod can never encounter another integration bug. Fresh-chunk stress testing remains required.

## 0.3.9 slab resolver audit

- The registered block registry is scanned once, lazily, to build an immutable block-to-slab cache.
- Countered Terrain Slabs remains optional and its explicit mappings take priority when available.
- Generic vanilla/modded matching fills only unmapped blocks using registered slab naming conventions.
- Road placement performs only an O(1) cache lookup after initialization; it does not scan registries or invoke Countered reflectively per road cell.
- Failure of Countered's optional API disables only the Countered lookup. Generic slab matching remains available.
- The runtime bridge still retains the outer Throwable fail-safe so a slab-compatibility fault cannot escape into world generation.

## 0.3.10 vegetation / optional-compat audit

- Road-intersecting tree cleanup is still structure-protected and hard-bounded; it cannot recurse or grow an unbounded work queue.
- The cheap road trigger scans only a 5x5 footprint and 19 vertical cells; the expensive cleanup runs only after tree/bamboo/attachment material is detected.
- Full tree cleanup is bounded to a 9-block canopy radius and 48-block vertical range. This is intentionally conservative: it catches normal and large trees without risking a forest-wide delete.
- Logs, tagged/modded leaves, bamboo, vines, cocoa, propagules, and mangrove roots are classified. Natural bee nests and unsupported canopy snow are handled by the follow-up cleanup; crafted beehives are excluded.
- `GooskerRoadVegetation`, `GooskerBeeNestCleanup`, and `TerrainSlabsCompat` use `ServerChunkCache#getChunkNow` for live `ServerLevel` access and direct `LevelChunk#getBlockState` reads. Missing chunks are skipped; none of these helpers request or wait for a chunk.
- Generic/Countered slab resolution remains cached once per session; Countered failures disable only its optional lookup, while the outer slab boundary still fails closed on unexpected runtime/linkage faults.
- Structure Void remains cleanup-only. The current source references `Blocks.STRUCTURE_VOID` only as `LEGACY_PREPARATION_BLOCK` for identifying/removing old alpha markers; this audit adds no staging placement.

The remaining intentional limitation is oversized custom vegetation: material farther than the bounded cleanup radius can survive rather than letting Road Architect aggressively erase neighboring forest. That is preferable to an unbounded/destructive cleanup pass.



## 0.3.12 positive natural-tree guard
- Whole-tree deletion requires positive natural-tree evidence: an unstripped trunk plus at least nine non-persistent leaves in a bounded 5x5 / 30-block detector modeled on Tree Harvester's logic.
- A persistent leaf causes that candidate to fail closed. This protects player-made vanilla leaf/log builds without storing every block placement.
- Cleanup radius is seven blocks horizontally and 48 vertically; the bee-nest/snow follow-up uses the same width.
- Logs are removed through a bounded connected-tree traversal rather than deleting every log in the cleanup cube. Off-core branches must have natural leaves nearby, reducing collateral damage to adjacent timber structures.
- Leaves are removed only when non-persistent. Vines/cocoa/propagules are removed only when connected to positively identified natural tree material.
- Traversals are capped at 2,048 logs and 4,096 attachments. Missing live chunks are skipped through `ServerChunkCache#getChunkNow`; no tree detector requests or waits for a chunk.
- Tree Harvester is not a runtime dependency; its supplied JAR was used only as a behavioral reference for the natural-tree detector.

## 0.3.13 post-generation invariants

- Road Architect is absent from biome generation stages; all roads are applied after completed chunks load.
- No 256-column cache snapshot or cache write is attached to CHUNK_LOAD.
- CHUNK_LOAD does not place roads or run broad structure scans.
- Only chunks with pending road segments enter the backfill queue.
- Backfill places at most one centerline point per server tick.
- Target-structure discovery waits for a completed 5x5 LevelChunk area and reads valid starts directly in the trigger chunk, without disk presence checks or placement planning.
- No periodic/player-join radius scan is run.
- Automatic async parallelism is one worker; explicit values are capped at two.
- Delayed item-entity cleanup scans are disabled.
- Live-world chunk access remains getChunkNow/loaded-only where safety-sensitive.

## Finished release checks

- One-point construction retains the complete path tangent; neighboring points provide geometry without being constructed in the same tick.
- Post-load construction includes deterministic lamp posts and side decorations. Marker ownership uses `(previous distance, current distance]` and is checked against whole-path placement across straight, diagonal, duplicate-point, and sparse paths.
- Structure Void and Barrier are rejected both in the weighted palette and immediately before road writes. Legacy Structure Void references remain removal-only.
- Fabric version/name are expanded from the same Gradle project version used for the output filename.
- A successful compile/remap and marker regression run do not establish performance in the user's complete modpack. Distant Horizons/Terralith/Tectonic integration still needs an in-game fresh-chunk run.

## 0.3.14 scope safety

- Repeated guard/vegetation results live only inside one consecutive post-load PREPARE/FINALIZE pair. The scope is removed in `finally` through try-with-resources, before decorations and before the next point. Existing buildings are checked at entry; later player/world changes are never served from an old scope.
- Door-section palettes are conservative: a false positive causes an ordinary scan, while sections without possible lower doors skip block reads. The section cache is local to a single endpoint lookup.
- Natural-height caching is distinct from live heightmaps, bounded to 131,072 columns per world and discarded when the world closes. Existing natural snapshots are reused, without launching new 256-column snapshot generation during refinement.
- Tree state classification is cleared on server start/datapack reload.
- The no-Structure-Void/Barrier guards and loaded-only access remain in place.
