# Road Architect: Goosker's Patch

Fabric 1.20.1 fork of [RoadArchitect 1.6.6](https://github.com/Shadscure/RoadArchitect), focused on stable non-blocking generation, structure protection, terrain-safe road placement, and natural road-network cleanup.

## Alpha 0.2.13

- Rejects building-supported and nearby generated-structure surfaces, including terrain-covered rooftops.
- Resolves every road-width cell to exposed natural ground and refuses unsupported ravine/cave placements instead of creating floating trail blocks.
- Makes preparation entirely non-destructive: only the final road surface replaces terrain, and later-generated flowers, leaf litter, snow, or ground vegetation are not rolled back.
- Deletes complete road-intersecting trees and bamboo columns while preserving isolated leaves and ordinary vegetation. Natural bee nests attached to trees removed by the road are cleaned up as well; crafted beehives are not targeted.
- Never creates a Structure Void staging block and removes legacy alpha 0.2.4/0.2.5 markers throughout the former preparation footprint.
- Strengthens smooth parallel-road convergence while retaining the original junction merge system and rejecting crossing/perpendicular merges.
- Retains alpha 0.2.9's bounded loose vegetation/tree-drop suppression near freshly generated trail cells.

## Optional Countered Terrain Slabs integration

Alpha 0.2.13 retains the soft compatibility with the `terrain_slabs` mod introduced in 0.2.10, but wires it directly into Road Architect instead of using a mixin.  There is no hard dependency: Road Architect runs normally when Terrain Slabs is not installed.

In alpha 0.2.13 the entire optional compatibility path is fail-safe: if Terrain Slabs changes its API or a linkage/runtime mismatch occurs, slab transitions are disabled for the remainder of that session and Road Architect falls back to normal full-block road steps instead of crashing world generation.

Keep **full blocks** in Road Architect's road block palette. Do not add every Terrain Slabs slab to the palette. When two finalized road cells meet at a one-block height difference, the compatibility layer uses Terrain Slabs' own block-to-slab mapping and inserts the matching bottom slab above the lower road cell to form a half-step transition. If the configured road material has no matching Terrain Slabs slab, the ordinary full-block step is left unchanged.

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

The Fabric JAR is written to `build/libs/Road-Architect-Gooskers-Patch-alpha-0.2.13.jar`.

## Source layout

- `src/main/java` — common and Fabric implementation
- `src/main/resources` — Fabric metadata, language files, and worldgen data
- `gradle/` and `gradlew*` — complete reproducible Gradle wrapper

This is an unofficial fork and remains licensed under Apache-2.0.
