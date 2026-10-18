# Road Architect 0.3.18 DH play-test fix

The remaining 0.3.17 Road Architect-side pressure point was the post-load builder cadence. `GooskerRoadBackfill.processOne()` requeued an unfinished road chunk and could process the next centerline point on the very next server tick. On a healthy 20 TPS server that is a 20 Hz live-edit cadence on the same chunk.

The earlier DH reproduction showed a hot chunk being retried inside an approximately 250 ms window while older ready work starved. A 20 Hz Road Architect stream never supplied that chunk with a quiet window.

0.3.18 therefore limits live road construction to one point every seven server ticks globally. That yields six complete quiet ticks between Road Architect road-write bursts (~300 ms at 20 TPS). The existing 0.3.17 fixes remain: deterministic no-repaint road cells, loaded-only backfill, one-point atomic work, and per-chunk throttled tree removal.

This is intentionally stability-first. It slows newly discovered road construction, but does not change the finished road layout or palette.
