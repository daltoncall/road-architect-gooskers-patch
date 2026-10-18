# Road Architect 0.3.17 chunk-update audit

The user reports that Distant Horizons previously worked with the same pack and stopped loading after changing Road Architect. That is the regression boundary for this audit. Spark's low Road Architect CPU percentage does not exclude indirect interference caused by block updates, earlier failures, or state left behind before profiling.

The source comparison uses alpha 0.2.8, 0.3.14 and 0.3.16. In the newer post-load architecture, a road is built one centerline point per tick. Each stroke overlaps nearby points, but its surface palette previously picked again from the mutable random stream. This repainted already-covered cells with different materials across ticks. The same behavior existed before the one-point architecture, but then overlapping work happened within larger generation/build batches.

0.3.17 picks the palette deterministically from world seed and cell position, retains the configured palette weights, and skips an identical target state. This keeps overlapping surface strokes from generating unnecessary live updates. It changes the random arrangement of newly painted road materials; it does not remove palette entries or alter their weights.

0.3.15/0.3.16 removal plans were flat cell queues. A 128-cell pass could touch multiple chunks and return to the same chunk every tick. 0.3.17 groups by chunk, processes at most one chunk and 128 candidates per world/tick, and leaves six quiet ticks before returning to that chunk. The pause is intended to give DH's chunk-update debounce time to settle; its actual end-to-end effect depends on the installed DH version and other updates. Unloaded and cooling chunks rotate so ready work can proceed.

Road FINALIZE/decorations previously ran immediately after PREPARE even when PREPARE had merely queued a tree for later removal. 0.3.17 keeps that persisted road point pending until nearby queued tree cells finish. It does not require a player command. Road/tree writes remain loaded-only, and the expected-block/player/structure guards are checked at deletion time.

Registration no longer injects road features into biome generation. The legacy feature entry point also now rejects non-ServerLevel generation regions, preventing old datapack references from restoring that path.

The submitted three-minute Spark capture shows DH's chunk-update dispatcher repeatedly popping/requeuing work, LOD builders approximately 96% idle, and during one full minute render loaders approximately 97% waiting for GPU uploads. The server remains close to 20 TPS. Those observations establish where time is spent; they do not establish which earlier Road Architect operation triggered the DH loading failure. Background FPS throttling is not treated as the user's changed variable.

Validation uses actual RoadFeature stripes on a counted loaded-world fixture, a byte-for-byte 0.3.16 source reference for the repaint comparison, and persisted multi-chunk removal fixtures. It retains giant-tree/build-protection, marker, and performance checks. These fixtures do not reproduce the user's full client/modpack, renderer, or affected world. This release corrects demonstrated Road Architect update defects; DH recovery is not claimed as confirmed.

## Reproduced comparison

The same 16-point flat path, one-point strokes, three-block width, palette configuration, world fixture and random inputs are used for both runs. The comparison loads the unchanged 0.3.16 RoadFeature source ahead of the same supporting classes; the patched run loads the production 0.3.17 class. Counts below distinguish setBlock calls from actual changed block states.

| Actual stripe fixture | 0.3.16 | 0.3.17 |
| --- | ---: | ---: |
| First traversal: setBlock calls | 144 | 27 |
| First traversal: changed block states | 97 | 27 |
| Repeated traversal: setBlock calls | 144 | 0 |
| Repeated traversal: changed block states | 109 | 0 |

The 0.3.16 reference fails the completed-road preservation assertion. The patched class passes with zero repeat writes and preserves the flower overlay. The table measures this specific fixture, not overall chunk generation or DH upload throughput.

The persisted deletion fixture interleaves 384 cells from two chunks. The patched queue completes them in four bursts, each touching one chunk and at most 128 cells, with six intervening quiet ticks before that chunk changes again. Additional tests cover unloaded chunks rotating fairly, saved work resuming, and the pending footprint used to delay road finalization. Legacy generation-region entry is rejected before any server/chunk access.
