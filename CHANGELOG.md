# Changelog

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
