# Road Architect 0.3.19 — Distant Horizons / chunk-priority fix

## What was actually causing pressure

0.3.18 already delayed live road writes and waited for loaded chunks, but two remaining behaviors could still interfere with smooth world streaming:

1. A not-ready road or structure request at the head of its queue returned immediately, so already-ready work behind it could be starved.
2. Pathfinding height cache misses could build a full 16x16 / 256-column noise snapshot for a chunk just to answer one height sample. This did not force-load the chunk, but it performed chunk-scale generator work on Road Architect's async worker while Distant Horizons/worldgen was trying to generate chunks.

## 0.3.19 behavior

- Chunk/world generation stays first: Road Architect never calls a blocking/force-loading `getChunk(...)` path in its post-load road/tree pipeline.
- Road and target-structure queues rotate not-ready work and continue looking for ready work within their small bounded poll budgets.
- Pathfinder height samples prefer `getChunkNow(...)` when the LevelChunk is already complete.
- Missing height samples use a single generator height lookup; they no longer create a new full `ChunkHeightSnapshot`.
- Live road construction remains one centerline point every 7 server ticks.
- Tree cleanup remains whole-tree cleanup within the road's 7-block detection radius and retains player-placement/generated-structure protection.

## Validation status

The actual 0.3.19 JAR bytecode was inspected after patching and confirms the scheduler branches and height-cache policy above. The archive passes `unzip -t`. The supplied playtest `.mrpack` retains Distant Horizons 3.3.2 unchanged and contains the corrected Item Obliterator config that previously prevented startup.

A clean Gradle rebuild could not be run in the current sandbox because the Gradle 9.1.0 distribution is not locally cached and outbound binary download is unavailable. Full in-game rendering/DH runtime therefore still needs the supplied playtest pack to be launched in Minecraft.
