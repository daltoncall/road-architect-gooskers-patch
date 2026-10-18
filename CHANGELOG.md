# Beta-1.3.21 - Safety-audited playtest build

- Restores normal Fabric road runtime callbacks; 0.3.20 was diagnostic-only and intentionally disabled them.
- Removes the 0.3.18 seven-tick DH quiet-window throttle after isolation testing proved the DH stall was caused by Chunky/DH 3.3.2, not Road Architect. Live construction returns to one bounded centerline point per server tick.
- Restores natural-generator terrain sampling for PathFinder so loaded roofs/logs/builds cannot become planned path elevations.
- Retains 0.3.19's safe cache policy: missing path heights use a single generator lookup and never synthesize a new 256-column chunk snapshot.
- Retains 0.3.19 queue fairness, loaded-only `getChunkNow` gates, no force-loading, 7-block tree cleanup, and structure/player-build protections.
- No tree-removal, build-protection, road palette, decoration, or saved-data format changes.

# 0.3.19 - Loaded-chunk-first / DH priority fix

- Stops a not-ready road chunk from blocking ready queued road work behind it.
- Stops a not-ready structure trigger from blocking ready structure work behind it.
- Pathfinding now checks an already-loaded LevelChunk first for terrain height.
- Road Architect no longer synthesizes a full 16x16 (256-column) terrain snapshot for an unloaded chunk; unloaded samples fall back to one generator height lookup instead.
- Resets the road-write cadence counter when the queue is cleared.
- Preserves 0.3.18's one-road-point-per-7-ticks write cadence, loaded-only road writes, 7-block tree cleanup, and structure/player-build protections.

# 0.3.18 - DH hot-chunk quiet-window fix

- Live post-load road construction now runs at most once every 7 server ticks instead of once every tick.
- This leaves six fully quiet ticks (~300 ms at 20 TPS) between Road Architect road-write bursts, longer than the ~250 ms Distant Horizons retry/debounce window observed in the failing play-test trace.
- Tree removal keeps its existing per-chunk six-quiet-tick limiter.
- No road style, pathfinding, structure targeting, or terrain-protection behavior changed.

# Changelog

## 0.3.17 — Stable road surfaces and coalesced tree edits

- Use a stable world-seed/position palette choice so overlapping live strokes do not repeatedly repaint completed road cells.
- Skip identical surface writes.
- Group persisted tree removal by chunk; process at most one chunk and 128 candidates per world/tick, leaving six quiet ticks before revisiting that chunk.
- Rotate unloaded/cooling chunks fairly without forced loads.
- Keep a road point pending until nearby queued tree removal finishes, before FINALIZE and decorations.
- Reject legacy placed-feature calls into world-generation regions, including references left by older datapacks.
- Retain automatic tree repair, placement/structure protection, terrain guards and 0.3.14 performance changes.
- Add actual stripe/write-count checks, interleaved-chunk scheduling checks and restart/retention checks. Full-pack DH recovery remains unverified until tested in the affected instance.

## 0.3.16 — Automatic damaged-tree cleanup

- Remove the cleartree command and its manual repair workflow.
- Automatically recognize rooted vertical canopy-stripped single and 2x2 trunks.
- Revisit saved READY road routes after chunk load through an indexed, one-point-per-tick worker.
- Abort connected-tree removal when wood is protected or attached to building evidence at any height.
- Remove the redundant tall proof-volume scan; retain bounded persisted removals and non-loading reads.
- Test damaged trees, high building attachments, horizontal timber, persistent leaves, partial-protection abort and saved route indexing.

## 0.3.15 — Complete giant trees and build identity

- Removed the unconfirmed orphan-canopy fallback.
- Extended natural-tree proof to 128 blocks and collect complete connected trunks/canopies before removal.
- Protect recorded player placements and loaded generated-structure bounds.
- Preserve neighboring canopy ownership and abort incomplete/oversized collection.
- Persist removals and limit writes to 128 candidate cells per world/tick.
- Add a targeted `/roadarchitect cleartree` command for known damaged trees.
- Retain 0.3.14 performance changes and invisible-marker guards.

## 0.3.14 — Performance audit

- Corrected structure classification to use registered block IDs, with exact air/fluid matching. Properties such as `waterlogged=false` no longer make chests/doors look like water, and stairs no longer match air.

- Reused bee-nest and snow candidates from the existing tree/canopy pass instead of scanning a second large cleanup cube. Crafted beehives and supported snow remain protected.
- Cached block classification and reused building guards during each post-load point. Consecutive PREPARE/FINALIZE phases inspect vegetation once; the scope ends before decorations and is discarded before the next point.
- Reused natural terrain-height snapshots and a bounded natural-height cache. Refinement no longer asks the general height cache and then repeats the generator noise calculation for the same column.
- Stopped sampling density/block state for already-resolved columns in chunk-height snapshots.
- Door scanning skips chunk sections whose palettes contain no candidate lower doors; the original entrance-selection rules remain.
- Indexed pending paths so idle ticks do not walk completed roads, and requeued interrupted PROCESSING paths after a world reload.
- Retained 0.3.13 post-generation-only construction, one-point tick budget, complete path tangents, deterministic decorations, slab transitions and invisible-marker guards.
- Added counted-world performance fixtures and preservation checks to the build. Full-modpack chunk throughput is not measured by these fixtures.

## 0.3.13 — Post-generation stability / Distant Horizons isolation

- Finished the release as a full source compile, with synchronized Fabric metadata and the missing Gradle wrapper binary included.
- Fixed the one-point builder to retain the full path tangent; diagonal/curved roads no longer fall back to an east-west tangent.
- Restored deterministic lamp posts and side decorations to the post-load builder; fixed marker interval ownership so one-point slices retain endpoint markers without repeats.
- Structure discovery now reads valid loaded starts directly, without disk presence checks or placement/ring planning. A start does not require structure references to trigger discovery.
- Added palette and final placement guards against Structure Void and Barrier, including accidental configuration entries.
- Added executable regression checks for decoration marker partitioning to the standard build checks.


- Removed Road Architect placed-feature injection from biome generation. Roads are now built only after chunks are completed/loaded.
- Removed CacheManager CHUNK_LOAD/CHUNK_UNLOAD hooks. Road Architect no longer scans 256 height columns or queues cache writes for every chunk Distant Horizons touches.
- CacheManager CHUNK_LOAD is a no-op and height data is lazy; loaded LevelChunks are used directly when available.
- Target structures now queue a tiny post-load trigger. Broad player-join and periodic radius scans were removed.
- CHUNK structure resolution waits for a completed 5x5 area (the entrance resolver footprint) and reads the trigger chunk's valid structure starts directly.
- Backfill no longer queues every loaded chunk. Only chunks with actual pending road segments are queued.
- Unloaded road chunks are not kept in a retry queue; CHUNK_LOAD wakes them when they actually load.
- Road construction is hard-budgeted to one centerline point per server tick. This intentionally favors smooth TPS over instant road completion.
- Removed reflective calls from post-load backfill; it now calls the road helpers directly.
- Async Road Architect work defaults to one worker and explicit configurations are capped at two workers to avoid competing with Distant Horizons/vanilla worldgen.
- Disabled delayed item-entity road-drop sweeps; road/tree removal already uses no-drop removal and the repeated entity scans were unnecessary tick work.
- Postprocessor pending lookup no longer copies the full status map every world tick.
- Retains 0.3.12 natural-tree detection, seven-block tree cleanup width, vines/tree attachments cleanup, 0.3.9 generic slab matching, structure protection, and all prior safety guards.


## 0.3.12
- Replaces the 0.3.11 timber-geometry heuristic with positive natural-tree detection modeled on Tree Harvester's bounded canopy check. A tree must have an unstripped trunk plus at least nine non-persistent leaves in a bounded 5x5 / 30-block scan before destructive whole-tree cleanup can begin.
- Player-placed vanilla leaves (`persistent=true`) cause that candidate tree to fail closed, protecting log cabins and decorative timber without maintaining a player-placement database. Stripped logs are never accepted as natural-tree evidence.
- Restores the requested seven-block horizontal cleanup radius while retaining the 48-block vertical cap. Bee-nest/snow follow-up cleanup uses the same seven-block width.
- Tree deletion now removes only the positively identified connected natural log skeleton, non-persistent leaves, and vines/cocoa/propagules connected to that natural material. Nearby ambiguous timber is left alone rather than deleting every log inside a cube.
- Orphan cleanup likewise removes only non-persistent natural leaves and attachments connected to them; arbitrary player vines/leaves are not swept just because they are near a road.
- Adds hard 2,048-log and 4,096-attachment traversal budgets. All scans remain loaded-chunk-only through `getChunkNow` on live worlds, with no new dependency on Tree Harvester.

## 0.3.11
- Adds conservative constructed-timber detection before destructive tree cleanup. Log cabins and timber structures are protected by geometry rather than nonexistent player-placement metadata.
- Stripped logs are treated as an unambiguous construction signal. Sparse-leaf timber with repeated straight runs, broad same-height layers, or separated vertical posts is treated as a structure and tree cleanup is skipped.
- The structure heuristic is bounded and runs only after trunk material actually intersects a road, avoiding a new per-road-cell registry/world scan.
- Retains 0.3.10 tree/vine cleanup, loaded-chunk-only vegetation reads, slab compatibility, and all stability safeguards.

## 0.3.10
- Stability/tree-cleanup audit; no intentional road-network, structure-connection, palette, or terrain-shaping behavior changes from 0.3.9.
- Expanded bounded road-intersecting tree cleanup from a 7-block/36-block canopy pass to a 9-block radius and 48-block vertical window, with a 16-block trigger scan above the road.
- Tree cleanup now removes attached vines, cocoa, and propagules along with logs/leaves, and treats mangrove roots as trunk material. Bamboo cleanup remains bounded and complete.
- If a canopy/vine crosses the road but the trunk is off-center, Road Architect searches the bounded tree area for the trunk and removes the complete intersecting tree rather than only punching a hole through the canopy.
- Orphan cleanup now removes leaf/vine/tree-attachment fragments even when the original trunk has already disappeared.
- Natural `minecraft:bee_nest` and unsupported snow cleanup follows the larger tree bounds; crafted `minecraft:beehive` is still never targeted.
- Vegetation, bee/snow cleanup, and slab-transition compatibility now use `ServerChunkCache#getChunkNow` + direct `LevelChunk` reads on live `ServerLevel`s, so these helpers cannot force/block on an unfinished chunk.
- Re-audited Structure Void handling: the marker constant is cleanup-only; 0.3.10 does not add any Structure Void placement path.
- All vegetation loops remain hard-bounded and structure protection still runs before tree removal so wooden generated structures are not treated as trees.

## 0.3.9
- Added a one-time cached block-to-slab compatibility map for road elevation transitions.
- Countered Terrain Slabs' explicit mapping table is bulk-copied once and retains priority for its terrain-specific slabs.
- Added generic registered-slab matching for vanilla and modded blocks, including wood planks, decorative stone, brick/tile plurals, and common `*_block` naming cases.
- Road generation now performs only an O(1) cached lookup; there is no registry scan or Countered reflection per road placement.
- Countered remains optional. If its API lookup fails, generic vanilla/modded slab matching continues instead of disabling the whole transition system.

## 0.3.8
- Version rename/release packaging of alpha-0.2.17.1.
- No gameplay, worldgen, or stability-code changes.

## alpha-0.2.17.1
- Packaging-only hotfix for alpha-0.2.17. No gameplay/worldgen code changes.
- Fixed release `fabric.mod.json` accidentally containing unexpanded Gradle placeholders for Fabric Loader, Minecraft, Cloth Config, and Mod Menu versions.
- Release metadata now explicitly targets Fabric Loader >=0.16.14, Minecraft ~1.20.1, Cloth Config >=11.1.136, and Mod Menu 7.2.2.
- Restored explicit nested Caffeine JAR metadata in the distributed JAR.
- Source `fabric.mod.json` now uses concrete compatibility values so copying an unprocessed resource cannot recreate this specific loader failure.

## alpha-0.2.17
- Stabilization-only audit; no intentional road appearance, entrance, graph, ice-road, snow-cleanup, or Terrain Slabs behavior changes from alpha-0.2.16.
- Removed the blocking `CompletableFuture.join()` path from height-snapshot contention. If another worker is already computing the same snapshot, callers return immediately and use the normal fallback instead of waiting.
- Removed the `ForkJoinPool.commonPool()` submit-and-join pattern from structure candidate planning. Candidate planning now runs sequentially on the caller and is capped at 8,192 candidates per scan.
- Hardened live-world backfill so the center and a road-width-aware safety margin must already be completed chunks returned by `ServerChunkCache#getChunkNow`; backfill never force-loads those chunks.
- Extended the outer pipeline failure boundary to catch `LinkageError`, covering binary/API failures such as `NoSuchMethodError` without swallowing fatal VM errors such as `OutOfMemoryError`.
- Added cycle, missing-parent, and hop-budget guards to path reconstruction so corrupted/malformed parent chains cannot create an infinite loop.
- Made backfill reflection initialization fail closed instead of throwing `ExceptionInInitializerError` if an internal method signature changes.
- Serialized region-cache flushes per `RegionColumnStore` instance to reduce concurrent temp-file/atomic-move races, especially on Windows.
- Capped automatic Road Architect async parallelism at 8 workers (explicit configured values remain respected) to reduce contention with Minecraft and other world-generation systems.
- Re-audited the alpha-0.2.16 structure entrance path: it continues to use `getChunkNow` + direct `LevelChunk#getBlockState` only and defers incomplete footprints.

## alpha-0.2.16
- Fixed the hard integrated-server hang introduced by alpha-0.2.15 entrance targeting. Endpoint scans no longer call `ServerLevel#getBlockState`, so they cannot trigger `getChunkBlocking` from inside a chunk-load callback.
- Door/entrance reads now use `ServerChunkCache#getChunkNow` and direct `LevelChunk#getBlockState` access only. Missing or not-yet-FULL chunks return immediately instead of being loaded or waited on.
- Added a full scan-footprint readiness gate before Valley Lodge or generic doorway detection. A chunk holder merely existing is no longer treated as safe; every chunk needed by the doorway scan must already be a completed `LevelChunk`.
- If the entrance scan area is incomplete, endpoint persistence is deferred: the node is skipped for that scan and can be rediscovered by a later CHUNK/PERIODIC scan once the area is ready.
- Keeps generic entrance targeting, Valley Lodge targeting, the two-road cap, snow cleanup, ice-road support, Terrain Slabs fail-safe, H-menu fix, and all earlier terrain/vegetation protections.

## alpha-0.2.15
- Added generic entrance targeting for standalone generated structures. Road Architect scans already-loaded blocks around a discovered structure for low ground-floor doors, probes both sides of each door to determine which side looks outdoors, and moves the road node to the nearest safe four-block-grid approach point outside that entrance.
- Preserved the dedicated Terralith `valley_lodge` entrance resolver while extending the same close-to-the-door behavior to structures such as `friendsandfoes:illusioner_shack`, houses, bakeries, and similar standalone builds.
- Multi-building complexes whose IDs identify them as villages/forts/towns/cities keep their original structure anchor so one random house entrance does not hijack the whole settlement node.
- Retains the two-road-per-structure cap from alpha-0.2.14.
- Removes unsupported vanilla snow layers left floating after Road Architect deletes an intersecting tree/canopy. Ground-supported snow is preserved.
- Allows road surfaces to generate on vanilla ice, packed ice, blue ice, and frosted ice so configured ice-road palettes can be used across frozen lakes. Actual water and lava blocks remain forbidden road terrain.
- Retains the alpha-0.2.13 Terrain Slabs fail-safe and all earlier debug, terrain, vegetation, drop-cleanup, bee-nest, and Structure Void fixes.

## alpha-0.2.14
- Added Terralith `valley_lodge` endpoint handling. Road Architect now looks for the lodge's unique lowest ground-floor spruce door while the generated structure is loaded and anchors roads just outside that entrance instead of aiming at the generic structure-locate position.
- The entrance scan is loaded-chunk-only, server-thread-only, and fail-safe; if the lodge cannot be resolved, Road Architect keeps the normal structure anchor.
- Limited newly created graph connections to a maximum of **two road edges per structure node**, preventing 3-4-path hubs and encouraging simpler chain-like road networks.
- Existing already-generated physical roads are not deleted; the two-road cap applies to newly created graph edges.
- Retains alpha-0.2.13 Terrain Slabs crash protection and every earlier terrain/vegetation/debug fix.

## alpha-0.2.13
- Fixed the Terrain Slabs transition crash caused by alpha-0.2.12 emitting `BlockState#getBlock()` with the wrong JVM return descriptor (`Object` instead of `Block`).
- Rebuilt the compatibility path with a strongly typed Minecraft 1.20.1 `BlockState#getBlock()` call.
- Added a fail-safe boundary around the entire optional Terrain Slabs integration: any future API/linkage/runtime failure disables slab compatibility for the current session and lets normal full-block Road Architect generation continue instead of crashing worldgen.
- Terrain Slabs remains optional and is still discovered reflectively; Road Architect has no hard dependency on it.
- Retains the alpha-0.2.12 H debug-map fix and all earlier terrain, vegetation, structure, drop-cleanup, and bee-nest fixes.

## alpha-0.2.12
- Fixed the Road Architect H debug-map hard lock/crash when adaptive grid spacing rounded a fractional value down to 0.
- Debug grid spacing is now clamped to a minimum of 1 block, preventing the render loop from ever executing `x += 0` / `z += 0`.
- Retains Terrain Slabs compatibility, bee-nest cleanup, drop cleanup, terrain, tree, and Structure Void fixes from 0.2.11 and earlier.

## alpha-0.2.11

- Fixed the alpha-0.2.10 startup crash caused by registering mixins against Road Architect's own classes.
- Removed the new RoadFeature/vegetation mixins from `fabric.mod.json`; ARRP is no longer involved in loading these hooks.
- Wired Terrain Slabs transition handling directly into `RoadFeature.placeRoad`.
- Wired natural bee-nest cleanup directly into the existing bounded tree/orphan-canopy cleanup.
- Terrain Slabs remains completely optional; its API is still discovered reflectively only when installed.
- Preserves all alpha-0.2.9/0.2.10 road, drop-cleanup, terrain, and structure-protection behavior.

## alpha-0.2.10

- Adds optional Countered Terrain Slabs integration for one-block road elevation changes.
- Treats slabs as transition geometry instead of random palette blocks: keep full blocks in Road Architect's block pool and matching Terrain Slabs bottom slabs are inserted only at eligible rises/descents.
- Uses Terrain Slabs' own block-to-slab mapping, so compatible road materials automatically receive their matching slab.
- Has no hard Terrain Slabs dependency; when `terrain_slabs` is absent, the compatibility layer is a no-op.
- Preserves existing flowers, grass, leaf litter, snow, and other road-top dressing: if the transition space is occupied, that cell keeps its decoration instead of being overwritten by a slab.
- Removes natural `minecraft:bee_nest` blocks orphaned by Road Architect tree/canopy cleanup while never targeting crafted `minecraft:beehive` blocks.
- Retains alpha 0.2.9's bounded loose vegetation/tree-drop suppression.

## alpha-0.2.9

- Preserves the current flowers, grass, petals, moss, and leaf-litter appearance on trails.
- Suppresses loose vegetation and tree drops caused by support changes or delayed canopy decay near freshly generated trail cells.
- Limits suppression by distance, item type, and a short cleanup window.

## alpha-0.2.8

- Commits post-processed paths, addon callbacks, and road-build queue changes on the server thread.
- Serializes post-processing per dimension to prevent shared-state races while new trails load.
- Recognizes tagged and modded leaves/logs and removes intersecting or orphaned canopies completely.
- Keeps leaf litter, flowers, and ordinary ground vegetation intact.

## alpha-0.2.7

- Prevented roads and decorations from using terrain-covered building rooftops.
- Added per-cell natural-surface resolution and unsupported-span rejection for caves, ravines, and overhangs.
- Removed destructive preparation rollback; non-road terrain and preserved overlays are left untouched.
- Expanded intersecting-tree removal to delete the full trunk and canopy; bamboo remains fully cleared.
- Added cleanup of legacy Structure Void markers across the old preparation radius.
- Strengthened parallel-road coalescing while keeping crossing/perpendicular safeguards.

## alpha-0.2.6

- Replaced physical Structure Void staging with in-memory preparation state.
- Removed the Structure Void requirement from final road placement.
- Added cleanup for legacy Structure Void markers left by alpha 0.2.4/0.2.5.
- Added a second bounded vegetation-clearance pass during road finalization.
- Retained alpha 0.2.5's zero-width clearance ring and identity backfill elevation behavior.

## alpha-0.2.5

- Removed the custom backfill centerline Y re-snap.
- Confined preparation to the actual trail footprint.
- Added bounded tree and bamboo cleanup for vegetation intersecting road cells.
