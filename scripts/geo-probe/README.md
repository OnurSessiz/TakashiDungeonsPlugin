# geo-probe — server-free tests for the generation package

Regression cover for phase 1B (geometry), 1C (selection + collision), 1D (graph generation) and
3B (spawn search). **No server needed**, and it runs in seconds — possible because the
`generation` package is deliberately pure Java, and because `RoomSpawnFinder` reads the world only
through the `ColumnProbe` interface. **133 checks** in total.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\geo-probe\run.ps1
```

`scripts\build.ps1` must have been run first (`target\classes` is required).

## Files

| File | What it does | When to run |
|---|---|---|
| `GeoProbe.java` | **1B — 53 checks:** rotation, `align`, wall derivation, collision rules, 48 placement combinations | on every change to the `generation` package |
| `GenProbe.java` | **1C — 28 checks:** weight distribution over 200,000 draws, pool filtering, backing off, consistency across 500 seeds, DEAD doors, reproducibility | same |
| `DungeonProbe.java` | **1D — 31 checks:** critical path guarantee (3×1000 generations), boss assignment, size ranges, plug coverage, consecutive-seed independence, out-of-the-box fallbacks | same |
| `SpawnProbe.java` | **3B — 21 checks:** the spawn search over hand-drawn ASCII rooms — L-shaped hall, corridor, blocked centre, two-storey, and a **sealed alcove the flood fill must not reach** | on every change to `RoomSpawnFinder` |
| `Rooms.java` | The shared test room set — in **alphabetical order**, matching `SchematicService.list()` on the server | (library) |
| `RotProbe.java` | Measures the sign of WorldEdit's `AffineTransform().rotateY(-degrees)` | only when the WorldEdit version changes |

## Two special sections in `GenProbe`

**Weight distribution** — the empirical evidence for the decision in `generation.md` §5.4. It
shows that `weight` belongs to the TEMPLATE (independent of door count), and computes, side by
side, how the ordering the mapper wrote would invert if it belonged to the pair. If this section
fails, the config is lying.

**Branching extinction** — not a failure, a **measurement**. It reports that the naive "fill
every open door" strategy does not guarantee the target room count, and that the stalls come
from the door frontier running dry rather than from collisions. 1D's critical path design rests
on these numbers; if they change, so has 1D's assumption.

## Two special sections in `DungeonProbe`

**Consecutive-seed independence** — it *first proves* that `new Random(seed)` is correlated
across consecutive seeds, then shows that the `Seeds` mixing fixes it. If this section fails,
small dungeons have had their room count pinned to a single value instead of a range.

**Out-of-the-box fallbacks** — it checks that generation does not stop when there is no boss
room, no entrance room, no rooms at all, or only single-door rooms. This is what backs the
project's out-of-the-box guarantee.

## They give the same answer as the server

`Rooms.java` keeps its templates in **alphabetical** order, because on the server the order
comes from `SchematicService.list()` and that method sorts file names. Since weighted selection
does a cumulative scan, the order changes the outcome.

The practical benefit: the probes can compute **in advance** what the server will build. That is
how the expected coordinates for the block tests are derived — an offline calculation matching
server output block for block is end-to-end proof that generation is reproducible.

`RotProbe` is not a one-off measurement but a **live assumption check**: every formula in the
`Rotation` class depends on that transform being clockwise. If WorldEdit ever flips the sign,
generated dungeons will have mismatched doors and the failure will be *silent* — the room pastes
fine, the passage comes out sealed. Run it after a version upgrade.

## Why not JUnit (yet)

There is no test dependency in `pom.xml`; adding one changes the build, and that decision has
not been made. `surefire` is already configured and skips because there are no test sources —
moving these under `src/test/java` is a single dependency line away.

The delay is not indecision: until phase 1 was finished, the test rooms and their expected
values changed rapidly, and so did these files. They move once the shape settles.

The files in this folder are **not part of the build** (they are not under `src/`), so there is
no risk of them breaking it.

## Expected output

```
################ FAZ 1B - geometri ################
GECEN: 53   KALAN: 0

################ FAZ 1C - secim + cakisma ################
GECEN: 28   KALAN: 0

################ FAZ 1D - graf uretimi ################
GECEN: 31   KALAN: 0

TUM TESTLER GECTI
```

(The probes' own console output is still Turkish; it belongs to the operator-facing message set
that gets translated as a whole.)

When a check fails, the formula that broke is printed line by line.

## What it does not cover

It does **not** show that a block really landed in the right place in the world — that is the
paste path's job, and it is verified on the server with `execute if block`
(`docs/generation.md` §10). This only answers the question "what coordinate does the engine
compute".
