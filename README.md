# Road Architect: Goosker's Patch — Beta-1.3.21

Fabric 1.20.1 fork of [RoadArchitect 1.6.6](https://github.com/Shadscure/RoadArchitect), focused on stable non-blocking generation, structure protection, terrain-safe road placement, and natural road-network cleanup.

**Documentation:** [Current release audit and verification](docs/CURRENT_RELEASE.md) · [Tree cleanup](docs/TREE_FIX_NOTES.md) · [Older technical reports](docs/README.md)

### Beta-1.3.21 DH / chunk-priority fix

Beta-1.3.21 keeps road writes post-load and bounded, rotates not-ready queue entries instead of starving ready work, and prevents pathfinding from generating full unloaded-chunk height snapshots. This gives Minecraft/Distant Horizons chunk loading priority while Road Architect waits and resumes after the needed chunks are present.

### 0.3.17 chunk-update audit

Road surfaces now resolve consistently per world seed and position, so overlapping tick-built strokes do not repaint the same cells with fresh random materials. Tree removal is grouped into one-chunk bursts with six quiet ticks before reuse, and a road point waits for its queued tree removal before final surfacing and decoration. Legacy road feature references cannot run against a generation region. See [the historical chunk-update audit](docs/archive/DH_REGRESSION_AUDIT.md) for measured checks and the remaining full-pack validation limit.

### Automatic tree cleanup

Road-intersecting trees and recognizable soil-rooted trunks damaged by older builds clear automatically. Existing saved road routes are rechecked after chunk load, one road point per server tick; removals are limited to one chunk and 128 candidate cells per world/tick, with six quiet ticks before returning to that chunk. No cleanup command or operator action is required. Recorded player placements, generated structure bounds and recognizable building attachments are protected. See TREE_FIX_NOTES.md for the damaged-trunk classifier and its limits.

### Automatic slab transitions
Road Architect 0.3.13 builds one cached block-to-slab map from the registered blocks in the game. Countered Terrain Slabs' explicit map is bulk-copied once and takes priority when that optional mod is installed, then ordinary vanilla/modded slabs fill any remaining matches (for example `oak_planks -> oak_slab`, `polished_granite -> polished_granite_slab`, and common modded `block -> block_slab` pairs). The cache is built once; road generation uses a constant-time lookup with no registry scan or Countered reflection per road cell.


### 0.3.13 stability architecture

Road Architect is now post-generation by design. It does not inject road features into biome generation and does not attach height-cache work to every chunk load. Target structures are discovered from their completed chunk, then queued for a loaded-start lookup after the entrance footprint is ready. Actual road placement is limited to one centerline point per server tick, so a road can take longer to appear but should not stall Distant Horizons or the integrated server.


## 0.3.14

**Preserved from 0.3.12:** Replaces the timber-shape heuristic with positive natural-tree detection modeled on Tree Harvester's core check. Whole-tree deletion now requires an unstripped trunk plus a canopy of non-persistent leaves; cleanup is limited to the connected natural tree and a seven-block radius.

## 0.3.10

**0.3.10:** Stability and tree-cleanup audit on top of 0.3.9 slab compatibility. Road-intersecting trees now receive a wider bounded cleanup pass that includes vines/cocoa/propagules and mangrove roots, while all live-world vegetation/slab reads remain non-loading.

- Rejects building-supported and nearby generated-structure surfaces, including terrain-covered rooftops.
- Resolves every road-width cell to exposed natural ground and refuses unsupported ravine/cave placements instead of creating floating trail blocks.
- Makes preparation entirely non-destructive: only the final road surface replaces terrain, and later-generated flowers, leaf litter, snow, or ground vegetation are not rolled back.
- Deletes complete road-intersecting trees and bamboo columns while preserving ordinary vegetation away from the affected tree. The bounded tree pass includes logs, leaves, vines, cocoa, propagules, and mangrove roots; natural bee nests and unsupported canopy snow are cleaned up as well, while crafted beehives are not targeted.
- Never creates a Structure Void staging block and removes legacy alpha 0.2.4/0.2.5 markers throughout the former preparation footprint.
- Strengthens smooth parallel-road convergence while retaining the original junction merge system and rejecting crossing/perpendicular merges.
- 0.3.13 disables delayed loose-item sweeps to reduce tick work; tree removal still uses no-drop removal.

- Terralith `valley_lodge` roads now target the actual ground-floor entrance when the lodge is loaded, rather than the generic structure locate point.
- New structure nodes are capped at two road connections, reducing four-way path spam and encouraging cleaner linked-road chains.
- Standalone structures now resolve toward a likely ground-floor exterior door when one can be identified from already-loaded blocks; village/fort/town/city complexes keep their broader original anchors.
- Keeps the two-road-per-structure cap introduced in alpha 0.2.14.
- Entrance targeting is now strictly non-loading: it only reads already-completed chunks via `getChunkNow`; incomplete scan areas are deferred to a later road scan instead of blocking the server thread.
- Cleans up unsupported thin snow layers orphaned by tree/canopy removal without touching ordinary supported snow.
- Treats vanilla ice, packed ice, blue ice, and frosted ice as valid road terrain while continuing to reject actual water/lava.

### Retained natural-tree cleanup guard

Healthy trees are identified from connected unstripped logs and non-persistent leaves. Damaged trunks also qualify through the rooted vertical-trunk classifier described in [the tree cleanup notes](docs/TREE_FIX_NOTES.md). Player placements, generated structure bounds, persistent leaves and adjacent building materials protect recognizable builds. Tree Harvester itself is not required or called at runtime.

The trigger is limited to the road footprint; connected-tree collection reaches a **16-block horizontal radius** and **128 blocks above the base**. Road Architect removes the connected natural log skeleton and its non-persistent canopy/attachments, then cleans natural bee nests and unsupported snow. On live `ServerLevel`s all reads remain `getChunkNow` + direct `LevelChunk` reads, so cleanup does not force-load or wait for chunks.

### Stability-only 0.2.17 audit

Alpha 0.2.17 is intentionally a stabilization pass rather than a road-generation feature release. The audit removes blocking waits, hardens loaded-chunk guarantees, bounds previously unbounded work, and makes optional/internal compatibility failures abort safely instead of taking down the integrated server.

- Height snapshot contention no longer waits on another computation with `CompletableFuture.join()`; an in-progress snapshot returns immediately and uses the existing safe fallback path.
- Structure candidate planning no longer submits to the common ForkJoin pool and immediately waits for it. Planning is synchronous, sequential, and capped at 8,192 candidates.
- Loaded-chunk backfill now requires completed chunks through `ServerChunkCache#getChunkNow` across a road-width-aware safety margin before touching live terrain.
- Pipeline boundaries catch `LinkageError` in addition to ordinary exceptions, so a future optional-API binary mismatch aborts that road operation instead of escaping into the server tick loop.
- Path reconstruction detects parent cycles, missing parents, and excessive hop counts instead of allowing malformed parent data to loop forever.
- Backfill reflection initialization fails closed: if an internal hook changes, backfill disables itself rather than throwing `ExceptionInInitializerError`.
- Region cache flushes are serialized per store to reduce competing Windows temp-file/atomic-move races.
- Automatic Road Architect async parallelism is capped at 8 workers to reduce contention with Minecraft/Distant Horizons world generation on high-core-count systems.

The alpha-0.2.16 non-loading entrance scanner remains in place: doorway detection reads only completed `LevelChunk`s obtained with `getChunkNow` and defers resolution when its footprint is incomplete.

## Optional Countered Terrain Slabs integration

Road Architect keeps `terrain_slabs` completely optional. When Countered Terrain Slabs is installed, 0.3.10 bulk-copies its explicit block-to-slab map once and gives those terrain-specific mappings priority. If Countered is absent or its API changes, the generic registered-slab resolver continues fail-safely.

Keep **full blocks** in Road Architect's road block palette. Do not add slabs to the random road palette. When two finalized road cells meet at a one-block height difference, Road Architect looks up the configured full road block in its cached map and inserts the matching bottom slab above the lower road cell. Vanilla wood/decorative slabs and standard-named modded slabs can participate even when Countered does not map the base block. If no unambiguous registered slab pair exists, the ordinary full-block step is left unchanged.

Existing trail dressing has priority. If the half-step position already contains preserved grass, flowers, leaf litter, snow, or another block, the slab is skipped at that cell instead of deleting the decoration.

## Requirements

- Minecraft 1.20.1
- Fabric Loader 0.16.14 or newer
- Fabric API 0.92.6+1.20.1
- Cloth Config 11.1.136 or newer
- Java 21 to run the current Gradle/Loom toolchain; generated mod bytecode targets Java 17
- Countered Terrain Slabs 3.1.0 is optional

## Build

Windows:

```powershell
.\gradlew.bat build
```

Linux/macOS:

```bash
./gradlew build
```

The Fabric JAR is written to `build/libs/Road-Architect-Gooskers-Patch-Beta-1.3.21.jar`.

## Source layout

- `src/main/java` — common and Fabric implementation
- `src/main/resources` — Fabric metadata, language files, and worldgen data
- `gradle/` and `gradlew*` — complete Gradle wrapper
- `docs/` — current verification, technical notes, and archived development reports

This is an unofficial fork and remains licensed under Apache-2.0.

## Verification

`./gradlew build` compiles the full source, remaps the Fabric release JAR, and runs `postLoadRegressionChecks`. The regression checks compare whole-path decoration markers with one-point tick slices, including diagonal distance prefixes, duplicated points, sparse paths, and final endpoints. They do not simulate the complete modpack or establish Distant Horizons performance.

## 0.3.14 performance audit

This build reduces repeated block/terrain scans while retaining the 0.3.13 post-generation architecture. Tree cleanup collects bee-nest/snow follow-up positions during its existing pass; per-point guards and vegetation checks are reused only until the point completes. Natural terrain height uses a separate bounded cache so live roofs/canopies cannot pollute it. Entrance detection skips sections without lower doors. Idle path processing uses a pending index.

`./gradlew build` also runs `performanceRegressionChecks`: counted world-access fixtures, persistent-leaf timber protection, natural-tree and bamboo removal, flower/beehive/supported-snow preservation, door palette checks, and pending/restart behavior. See [the historical performance audit](docs/archive/PERFORMANCE_AUDIT.md) for comparison scope and measured operation counts.

## Connected-tree planner

Connected trunks and canopies are collected together, then removed automatically in persisted batches with player-placement and generated-structure protection. See [the tree cleanup notes](docs/TREE_FIX_NOTES.md) for identification rules and limits. Cleanup requires no operator action.
