# Road Architect 0.3.14 performance audit

## Outcome

The code audit found unnecessary block scans, repeated natural terrain sampling, and work proportional to the entire saved road network. Version 0.3.14 reduces those operations while keeping 0.3.13's post-generation architecture and existing road features. The full source build and executable checks passed.

These are code and counted-world fixture results. They do not establish a Minecraft chunks-per-second improvement or confirm that every freeze in the full modpack is fixed.

## Comparison scope

The user's “0.12.8” reference was treated as alpha 0.2.8. The audit compared its actual source archive with alpha 0.2.12 and the finished 0.3.13 source. The 0.2.8 JAR was also recovered. No older performance baseline was silently substituted.

Alpha 0.2.12 itself changes the debug grid clamp; the generation-related differences since 0.2.8 were introduced by the preceding builds. The principal additions are loose-item cleanup, bee-nest cleanup, and slab transition tracking. The postprocessor's repeated natural-height calculation already existed in 0.2.8/0.2.12, so it is a retained hotspot, rather than evidence that 0.2.12 introduced it.

## Findings and corrections

| Work | Finding | 0.3.14 correction |
| --- | --- | --- |
| Tree follow-up | Bee nests/snow were found by a second large volume scan after an existing canopy scan. Branching trunks expanded the follow-up bounding box. | Collect follow-up positions during the existing pass, then check those candidates and positions above removed material. |
| Placement guards | The same point was repeatedly scanned for nearby buildings during preparation, vegetation removal, final placement and slab checks. | Reuse results for the consecutive PREPARE/FINALIZE pair. Discard the scope before decorations and the next point. |
| Vegetation | PREPARE and FINALIZE repeated the same vegetation inspection even though both now run consecutively after chunk generation. | Inspect a cell once per post-load point; later points still recheck it. |
| Terrain refinement | The general/live height lookup could compute/cache terrain, followed by another uncached generator height calculation. | Use a separate bounded natural-height cache and reuse existing natural snapshots. Avoid new full-chunk snapshots during refinement. |
| Height snapshots | Resolved height columns still sampled density/block state as the scan descended to resolve other columns. | Check whether a column is resolved before sampling its block state. |
| Entrances | Generic entrance search visited up to 49 x 49 x 27 = 64,827 blocks, including sections with no doors. | Inspect section palettes and skip sections without candidate lower doors. Preserve original doorway scoring/selection. |
| Idle processing | Each tick walked completed path statuses to find pending work. | Maintain a pending-key index; interrupted PROCESSING paths return to PENDING after reload. |
| Block classification | Full state text made `waterlogged=false` match water and stairs match air. | Use registered block IDs and exact air/fluid IDs. Protection fixtures include changes between scopes and separate worlds. |

The delayed loose-item sweeps were already disabled in 0.3.13; 0.3.14 does not claim an additional speedup from disabling them again. Slab transitions and their optional fail-safe cache remain.

## Measured checks

| Fixture | Earlier work | 0.3.14 work |
| --- | ---: | ---: |
| Eight repeated building-protection checks at one point | 3,376 block reads without a scope | 422 block reads with a scope (87.5% fewer) |
| Bee/snow follow-up in the same representative cleanup footprint | 12,377 block reads using the retained 0.3.13 cube-scan reference | 6 candidate/support reads |
| Repeated PREPARE/FINALIZE vegetation inspection | Repeated scan | Second same-point scan eliminated; next point rechecks |

The cleanup figure measures the follow-up only. The original bounded canopy pass still runs, so this is not a claim of a 2,000x improvement in total tree cleanup or chunk generation.

Additional checks passed: complete natural-tree and bamboo removal; orphan bee-nest and unsupported-snow cleanup; preservation of flowers, crafted beehives, supported snow and persistent-leaf timber; separate-world/expired-scope protection; door and trapdoor palette selection; 10,000 completed roads with pending/reserve/retry/restart transitions; 600 decoration marker partition checks plus the final endpoint check.

## Retained behavior

- Road Architect remains absent from biome feature injection and chunk-load height-cache work.
- Construction remains loaded-only and limited to one centerline point per server tick.
- Full path tangents, deterministic lamp posts/side decorations, cached slab matching, natural-tree guards, terrain support and existing road merging remain.
- Structure Void and Barrier placement guards remain. Legacy Structure Void references are cleanup-only.
- Natural-height caches are bounded to 131,072 columns per world and discarded at world shutdown. Tree classification is cleared on server start/datapack reload. Door-section caches last only for one endpoint lookup.

## Practical validation still needed

Test 0.3.14 in fresh chunks with the same seed, mod list, render/simulation distance and DH settings as the older build. A two-minute all-thread Spark profile covering an actual freeze is still needed to identify any remaining cause. The earlier submitted Spark profile covered only 0.133 seconds and is insufficient for that attribution.

The fixtures do not run DH, Terralith or Tectonic world generation. No full-pack TPS, MSPT, freeze-duration or chunk-throughput number is asserted here.
