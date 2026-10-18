# 0.3.16 automatic tree cleanup

0.3.17 retains the automatic classifier and build guards below, but groups pending removal cells by chunk. Each burst checks at most 128 cells in one chunk, then leaves six quiet ticks before returning to that chunk. Road finalization waits for nearby pending tree cells. This is intended to reduce repeated live chunk updates while still completing removal automatically.

Tree cleanup requires no operator action. The cleartree command from 0.3.15 is removed.

New roads use the connected-tree planner. Existing saved READY road routes receive a cleanup pass when their chunks load. CHUNK_LOAD only queues an indexed route notification; one saved road point is checked per server tick across all worlds. Missing surrounding chunks postpone the check without forcing loads. Routes are indexed by chunk while loading/updating path data, so unrelated DH chunks do not cause whole-path or whole-world scans.

The same planner automatically recognizes damaged, canopy-stripped trunks: one column or a 2x2 footprint of continuous vertical unstripped logs, at least five blocks tall per column, rooted in dirt/podzol/moss/mud or dirt path. It rejects unsupported poles, horizontal frames and mixed-material wood assemblies. Healthy trees retain connected natural-canopy proof. The obsolete separate 5x5-by-128 proof scan has been removed.

Player placement records and generated structure bounds remain protected. Connected protected wood aborts the whole plan, rather than allowing partial chopping. The planner also checks adjacent building evidence along the entire trunk, including high planks, stripped wood, persistent leaves, doors and other distinctive build blocks.

All removal plans are collected before any edit. Live removals remain saved, deduplicated and limited to 128 candidate cells per world/tick; every write rechecks its expected block and protection. Collection limits, unloaded boundaries, neighboring canopy ownership and preservation of ground vegetation remain in place.

Block states cannot reveal who placed an old, unrecorded log column that exactly copies a natural tree. The automatic damaged-trunk classifier is a heuristic; it may classify such a deliberately identical isolated soil-rooted pole as a tree. Recorded player blocks, identified structure bounds, and recognizable timber assemblies are excluded. Old trees outside saved road routes are not globally scanned.

Regression fixtures cover fully bare and healthy 96-block 2x2 spruce, a damaged single trunk, unsupported poles, high building attachments, horizontal beams, persistent leaves, protected connected wood, generated bounds, neighboring canopies, chunk boundaries, saved removal budgets and path-index restart/update behavior. Full-pack world generation is not simulated by these checks.
