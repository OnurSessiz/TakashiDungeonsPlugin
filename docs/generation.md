# Generation — the dungeon layout engine

> **Status:** Phase 1 is complete — 1A / 1B / 1C / 1D are written and all four verified on a
> live server. `/tdungeons dungeon <size> [seed]` produces an empty but fully walkable dungeon.
>
> This document is not a design proposal. It is the **specification of code that exists**, and
> every formula in it has been measured rather than assumed.

---

## 0. Why this design

Rooms are not generated procedurally. They are **arranged** procedurally. The rooms themselves
are schematics drawn by hand; the engine's only job is deciding which room goes where, at what
angle.

That framing rules out the two usual candidates. Cellular automata produce caves, not rooms.
BSP splits produce rectangles that hand-drawn rooms don't fit into. What remains is
**socket / jigsaw placement** — the same method Mojang uses for villages, bastions, ancient
cities and trial chambers.

---

## 1. Two separate grids — don't conflate them

There are two things called a "grid" in this project. They are not the same thing.

| Layer | What it does | Status |
|---|---|---|
| **Instance slot grid** | Keeps two parties' dungeons apart. 512-block squares, `index → position` deterministic. | Written (1A) |
| **Layout inside a dungeon** | Connects rooms to each other. No cells — **anchor-based free placement**. | Written (1B–1D) |

A dungeon lives inside a single slot. Within that slot, rooms do not sit on cells; they clamp
to each other at their door points.

> The original design used a fixed cell grid inside the dungeon too. It was **abandoned**.
> Section 2 explains why.

---

## 2. Core concepts

### Room template
A `.schem` file plus a `.yml` of the same name. A template is used by **rotating** it, never by
duplicating it into door variants.

### Origin = the room's center
The clipboard origin sits at the room's **horizontal center, at floor level**. Rotation always
happens around the origin, so a centered origin means the room turns on its own axis and the
paste target can be computed directly.

### Door anchor
The **base-center block of the door opening**, stored as a local coordinate relative to the
origin.

This has two consequences and both are load-bearing:

1. **Facing is not stored, it is derived.** The anchor vector is already a delta from the
   center, so the wall follows from it (§4). We never write `facing: north` in metadata, which
   means the failure mode "metadata says north but the anchor is in the east wall" cannot
   exist.
2. **A door doesn't have to be centered in its wall.** Because the anchor is written
   explicitly, a door can sit anywhere along a wall, and one wall can hold several doors.

### Why anchors instead of cells
- Rooms **don't have to share a size**. A 9×25 corridor, a 17×17 hall and a 33×33 boss arena
  can coexist in one dungeon. A cell grid forced all of them into one measurement.
- **Rotation is computed, not searched** (§5). Under the cell scheme the candidate pool got
  narrowed to "rooms with a west-facing door"; here every room is a candidate for every
  connection.
- **Straight runs break up on their own** — door offsets produce lateral drift (§6.4).

The cost: collision testing is now mandatory (§5.3). On a cell grid, overlap was
mathematically impossible.

---

## 3. Coordinate conventions

```
Minecraft:   +X = East    +Z = South    North = -Z
Direction:   N=0   E=1   S=2   W=3      (clockwise, viewed from above)

opposite(d) = (d + 2) mod 4
step(d)     = N:(0,0,-1)  E:(1,0,0)  S:(0,0,1)  W:(-1,0,0)
```

### Rotation
`R ∈ {0,1,2,3}` = number of 90° clockwise steps.

```
Direction:  d' = (d + R) mod 4

Point:      R=0 → ( x, y,  z)
            R=1 → (-z, y,  x)
            R=2 → (-x, y, -z)
            R=3 → ( z, y, -x)
```

Y is untouched — `rotateY` only turns the X-Z plane. That gives multi-storey room support for
free (§5.2, step 4).

**Sanity check:** the north unit vector is `(0,-1)`. Applying R=1 gives `(1,0)` = East. In the
direction index, `0+1=1` = East. Consistent.

### The WorldEdit side
The code uses `AffineTransform().rotateY(-degrees)` so that it matches WorldEdit's own
`//rotate` command. The direction a mapper sees in their editor and the direction the engine
produces have to be identical.

> **Measured — `+1` is correct, the sign is clockwise.**
>
> The exact transform used by `SchematicService` during paste was run directly on the JVM. No
> server was needed; the transform is pure math.
>
> | R | point | direction mapping |
> |---|---|---|
> | 0 | `( x, y,  z)` | N→N E→E S→S W→W |
> | 1 | `(-z, y,  x)` | **N→E E→S S→W W→N** |
> | 2 | `(-x, y, -z)` | N→S E→W S→N W→E |
> | 3 | `( z, y, -x)` | N→W E→N S→E W→S |
>
> All four matched the point formulas above exactly. The competing hypothesis
> `d' = (d - R) mod 4` was eliminated at R=1 and R=3. `test_corner` (N+E) rotated 90° comes out
> as **E+S**, which is what the formula predicts.
>
> This was measured before the code was written, because if the sign were inverted, every
> generated dungeon would have mismatched doors and the failure would be **silent** — the room
> pastes fine, the passage just comes out sealed.

---

## 4. Which wall is a door on — the center-delta method

The anchor vector `v = (dx, dy, dz)` is already a delta from the center. The wall falls out of
it.

**The naive rule (correct for square rooms only):** whichever component has the larger absolute
value picks the axis.

It breaks on rectangles. Take a 9-wide × 25-long corridor with a door in the east wall near the
south end:

```
v = (dx=+4, dz=+11)
|dz| > |dx|  →  "south wall"      ← WRONG, the door is in the east wall
```

**The correct rule — normalize against the half-extents:**

```
nx = dx / halfWidth      // 4 / 4  = 1.00
nz = dz / halfLength     // 11 / 12 = 0.917

|nx| >= |nz|  →  dx > 0 ? EAST  : WEST
else          →  dz > 0 ? SOUTH : NORTH
```

The reasoning: whichever component reaches ±1 is the one touching a wall. In a square room both
formulas agree — which is exactly why this bug is **invisible** in test rooms and detonates the
first time a rectangular room shows up.

**There is a permanent test room for this trap:** `test_long` (9 wide × 25 long) with a door in
the east wall near the south end, `v = (+4, +10)`. The naive rule says "south" because
`|dz| > |dx|`; the normalized rule gives `nx = 4/4 = 1.00 > nz = 10/12 = 0.83` and answers
**east**. Verified on the server. If the formula ever regresses, this room catches it
immediately.

**Normalization is done per direction**, so east and west extents are read separately. The
reason is §9: since the odd-side-length rule was dropped, the origin no longer has to be at the
exact middle of the room, and a room may be asymmetric about it. A single "half width" value
would give the wrong answer on an asymmetric room.

---

## 5. The placement algorithm

A new room is attached to an open door of an already-placed parent room.

### 5.1 Input
```
A_p  = the parent door's anchor, in WORLD coordinates
d_p  = that door's outward facing, in the WORLD frame
       (the parent's own rotation is already applied)
```

### 5.2 Steps

**1) Pick a child candidate.** A candidate is a `(room template × one of its doors)` pair. Note
that rotation is solved over **the pair**, not the template: a 3-door room can be seated three
different ways, and which door it connects through determines where its other doors end up.
That is where the variety comes from.

**2) Compute the rotation.** It is not searched for; it is a single value:

```
v_c = the child door's local anchor
d_c = wall(v_c)                        // §4
R   = (d_p + 2 - d_c) mod 4
```

*(Equivalent to: keep rotating +90° until `d_c` is the opposite of `d_p`. Same answer, four
iterations.)*

**3) Compute the position — the back-to-back convention.**

```
O_c = A_p + step(d_p) - rotate(v_c, R)
```

After placement the child's door anchor lands on `A_p + step(d_p)`: exactly one block outside
the parent's anchor. The two walls end up back to back, both have a hole in them, and the
passage is open.

> **Why back-to-back rather than overlapping:** if the walls overlapped, the second paste would
> overwrite the first one's wall and the result would depend on **paste order**. Generation with
> order-dependent output cannot be debugged. Back-to-back, no room ever touches another room's
> blocks. Side benefit: the 2-block passage reads as a thick door frame.

**4) Y aligns itself.** Because rotation preserves Y, `O_c.y = A_p.y - v_c.y`. The child's floor
lands wherever it must for its door's Y to meet the parent's. Multi-storey rooms — enter from
below, continue above — need no extra code; the anchor's Y already does the work.

**5) Collision test.** ← *This step is as mandatory as the rest of the algorithm.*

```
box = the child's 3D AABB with R and O_c applied

if box exceeds the slot bounds                → reject candidate
if box intersects any already-placed room     → reject candidate
```

**It has to be 3D**; a 2D footprint is not enough. In a multi-storey dungeon two rooms can
overlap in footprint without overlapping in volume — which is precisely the thing you want to
allow.

### 5.3 Backing off

```
candidates = shuffle( all (template × door) pairs )
for each candidate:
    compute R, O_c
    if the collision test passes → place it, done
if none passed:
    mark this door DEAD → it gets plugged in §7
```

Scale is not a concern: the largest dungeon is 20 rooms, so brute force means 20 box tests. A
spatial hash would be pointless.

---

### 5.4 Candidate selection — who owns the weight

> **Decision: `weight` belongs to the TEMPLATE, not to the `(template × door)` pair.**
> Selection is therefore two-stage.

```
1) pick a TEMPLATE     — weighted random, drawn from the pool without replacement
2) pick a DOOR         — that template's doors are shuffled and tried in order
3) if none fit         — the template leaves the pool, go back to 1
4) if the pool empties — the door is DEAD (§7 plug)
```

**Why not the pair.** Since a candidate is a `(template × door)` pair, a 4-door room produces
four candidates. If the weight belonged to the pair, that room would count its weight four
times — a property the mapper never wrote (door count) would override the value they did write.

With the current test set, concretely:

| Room | Doors | Weight | If pair-owned | **Template-owned (chosen)** |
|---|---|---|---|---|
| test_corridor | 2 | **150** | 21.4% | **25.4%** |
| test_corner | 2 | 120 | 17.1% | 20.3% |
| test_cross | 4 | 100 | **28.6%** | 16.9% |
| test_even | 3 | 80 | 17.1% | 13.6% |
| test_long | 2 | 80 | 11.4% | 13.6% |
| test_deadend | 1 | 60 | **4.3%** | 10.2% |

It isn't just that the distribution shifts — **the ordering inverts**. The mapper wrote 150 for
the corridor and 100 for the cross; under pair ownership the cross (28.6%) would outrank the
corridor (21.4%). The config would mean the opposite of what it says.

And the distortion **compounds**: the more multi-door rooms get placed, the more open doors
appear, and every one of those open doors again favours multi-door rooms. Over a 20-room
dungeon this is a growing bias, not a one-time one.

**The user-facing reason:** `weight` is a value server owners edit in YAML. The expectation
"if I write 200 it shows up twice as often as 100" has to hold. Door count silently overriding
it would be a bug nobody could diagnose, because the cause is written down nowhere.

**The counter-argument, and why it doesn't win.** A 4-door room genuinely is more useful to the
engine: it answers more geometric situations and keeps the graph growing. But that is a
**separate concern** and must not be smuggled into the mapper's weight. If we want to encourage
branching, that gets an explicit config knob that can be turned off — which is exactly how the
turn bias in §6.4 is built: it sits in the candidate ordering as its own coefficient rather
than being folded into the weight.

**Pool filter:** `entrance` and `boss` are **not** in the normal candidate pool. Both are
assigned in §6.2; if they stayed in the pool, a boss room could materialize in the middle of a
dungeon.

---

## 6. Graph generation

### 6.1 Size → room count
| Size | Target rooms |
|---|---|
| small | 3–6 |
| medium | 7–12 |
| large | 13–20 |

### 6.2 Critical path first
```
1. pick a target room count                        (medium → e.g. 10)
2. path length = round(target × 0.65), min 2       (→ 7)
3. chain rooms from the entrance (type: entrance)  (path length - 1 rooms)
4. SIDE BRANCHES grow — quota up to target-1       (one room reserved for the boss)
5. the boss attaches to the open door FURTHEST from the entrance   (assigned, not rolled)
```

> ### Why the boss is attached AFTER the side branches
>
> The first implementation attached the boss to the end of the path, **before** side branches
> grew. Two bugs were measured, and reordering removed both:
>
> **1) A `small` dungeon could never produce 3 rooms.** `round(3 × 0.65) = 2`, so the path is
> entrance + boss. The entrance (single-door `test_entrance`) spends its only door on the boss,
> and the boss is terminal — leaving **no open door** for a side branch. The third room could
> not be placed, generation restarted, and landed on a different target. Measured: across 800
> seeds the `small` target **never once came out as 3**, always 4–6.
>
> **2) Dungeons could generate with no boss at all.** The boss tried a single door; if the
> 33×33 room collided, the dungeon shipped bossless — 4 times in 2000 medium generations, 7
> times in 2000 large. A bossless dungeon gives the player nothing to aim at.
>
> In the new order the boss tries **every** open door, in descending order of depth. That keeps
> the "furthest from the entrance" rule while ending the situation where one collision drops the
> boss entirely.
>
> | | old order | **new order** |
> |---|---|---|
> | small: path complete | 99.0% | **100%** |
> | small: room count met | 87.5% | **100%** |
> | small: average attempts | 3.16 | **1.17** |
> | medium: generations with warnings | 0.1% | **0%** |
> | bossless (2000 medium) | 4 | **0** |

**Why the path comes first and the boss is assigned last:** if you scatter rooms randomly and
then say "put the boss in the furthest one", you have no control over how far the boss is from
the entrance — some dungeons end in 2 rooms, some in 15. Building the path first
**guarantees playtime**. That is the whole trick in procedural generation: randomness for
variety, skeleton for guarantees.

> ### This stopped being a theoretical argument — it was measured
>
> The naive "fill every open door" strategy does **not** guarantee the target room count.
> 2000 seeds, target 12 rooms, 512 slot:
>
> | Measurement | Result |
> |---|---|
> | Reached the target | **70.8%** |
> | Stalled | 584 runs |
> | ...of which had a dead door | only **81** |
> | Stopped at exactly **2 rooms** | **204** (10.2%) |
>
> So **86% of the stalls were not collisions** — they were the door frontier running dry. This
> is a **branching process (Galton-Watson) going extinct**: each placement consumes 1 door and
> adds `(doors − 1)`, i.e. a net `(doors − 2)`. With the current room set the expected net
> change is **+0.373 per room** — positive, but starting from a single-door root makes early
> extinction likely.
>
> **The 10.2% figure is not a coincidence:** `test_deadend`'s draw rate is 10.17%. The root
> (`test_entrance`) has one door; if the first room drawn is a dead end, the frontier empties
> immediately and the dungeon ends at 2 rooms.
>
> **The concrete requirement this created:** while building the critical path, **single-door
> templates must be excluded from the pool** (until the boss is assigned as the final node).
> Same 2000 seeds, with single-door rooms filtered out: **97.1%**. Dead ends are welcome on side
> branches — that is where you want things to end.
>
> **The fix belongs in the code, not in the maps.** "Make the entrance room have at least 2
> doors" is the other obvious idea, but measurement shows it is the wrong diagnosis
> (3000 seeds):
>
> | Entrance doors | Normal pool | Reached target |
> |---|---|---|
> | 1 | full | 70.0% |
> | 1 | **single-door filtered** | **97.3%** |
> | 2 | full | 92.1% |
> | 2 | single-door filtered | 99.8% |
> | 4 | full | 99.2% |
>
> The pool filter alone is enough even with a single-door entrance. A 2-door entrance helps too,
> but less, and it would constrain the entrance design of all four biomes. **Entrance door count
> is therefore not handed to the map team as a rule** — it stays a gameplay decision.
>
> Note: these numbers were measured with the naive "fill every open door" strategy. The
> path-first approach chases the target length explicitly and can retry, so it will be **at
> least** this good — the table is a lower bound.

### 6.3 Side branches
When the critical path enters a room, that room's other doors are left **unused**. Side branch
generation starts from those open doors and continues until the quota runs out or space does.

Multi-door rooms (3–4 exits) are the branch points. The player walks into a room, sees three
exits, one of which leads to the boss and two to loot, and doesn't know which is which. That's
the maze feeling.

### 6.4 How straight runs get broken up
Two mechanisms, both in use:

**Door offsets (the real engine).** If every door sat in the middle of its wall, rooms chained
northward would line up as if drawn with a ruler. If A's north door is 5 blocks right of center
and B's south door is 3 blocks left of its center, B seats 8 blocks laterally off. Accumulated
along a chain, those offsets make the layout meander on its own.

**Turn bias.** Options that continue in the parent's direction get a penalty.

The penalty is applied at the **door selection** stage (§5.4 step 2), not at template selection.
The reason is §5.4: touching the template stage would corrupt the meaning of `weight`, which we
just went out of our way to protect. Which door the child connects through determines its
orientation, and therefore where its remaining doors point — the penalty belongs there.

A door option counts as "straight" if, after placement, **another** of the child's doors points
along the parent's outward direction. In a room with two opposite doors (a corridor) both
options are straight, so the penalty has no effect — an honest outcome, since door offsets are
the real mechanism anyway.

---

## 7. Plugs — sealing open doors

When the graph is finished, some doors will still open into the void. Two reasons:
- the side branch quota was full, so the door was never tried
- it was tried, but every candidate collided (§5.3 → DEAD)

**Decision: don't duplicate the room, seal the door.**

Because the position of a door opening is fixed by specification, the engine can lay wall into
it. Two implementations, both supported:

| Method | Map work | Appearance |
|---|---|---|
| **Procedural** — engine measures the opening and fills it with the wall's own texture | 0 files | Indistinguishable in a flat-walled room |
| **Plug schematic** — a hand-drawn piece | 4 files (1 per biome) | For a decorated frame |

### What is written: procedural plugs (`schematic/DoorPlugger`)

**The opening's size is NOT in the metadata — it is measured.** Starting from the anchor, the
engine scans air blocks in the room's wall plane and finds the opening itself. This is the
spirit of §9: every field that can be written is a field that can be written wrong. If a mapper
draws a 3×4 door, the plug still fits; arched, stepped and asymmetric openings work too.

**The material is measured too.** The plug block does not come from config — the wall block
*around* the opening is sampled (most frequent wins). A Nether room plugs itself with nether
brick, an End room with end stone, automatically. That delivers nearly all of the benefit of the
"biome plug" option for **zero files**; the 4-file route is now only needed if you want a
specific decorative look.

**The scan is bounded by the room's box.** A wall plane (say `z = Z0`) is mathematically
infinite, and outside the room that plane is void — that is, air. Without the bound the scan
would leak out of the opening into empty space and fill half the dungeon with wall.

**A 64-block ceiling** guards against broken metadata: if an anchor sits inside the room rather
than in a wall, the scan begins on an empty interior plane. If the limit is exceeded, that door
is skipped and a warning is logged — the room is never silently filled.

### Why there is no door-count variant system
The idea "give every room a 1/2/3-door version and use one fewer when a door is left open" was
evaluated and **rejected**. The reason is mathematical:

A variant is not defined by the door **count**, it is defined by the door **set**. In a 3-door
`{N,E,S}` room, if east is left open you need `{N,S}` (opposite pair); in another scenario you
need `{N,E}` (adjacent pair) — different geometry, different walls. There is no single file
called "the 2-door version".

With 4 directions there are 15 possible combinations. Rotation collapses them to 5 shapes:

| Shape | Example | Produces via rotation |
|---|---|---|
| Single door | {N} | 4 |
| Opposite pair | {N,S} | 2 |
| Adjacent pair | {N,E} | 4 |
| Three doors | {N,E,S} | 4 |
| Four doors | {N,E,S,W} | 1 |
| | | **15 total** ✓ |

The math is clean, but so is the cost: 40 rooms × 5 shapes = **200 schematics**. The map load
we just cut from 120 to 40 by deciding to rotate would go back up to 200. The map team is this
project's tightest bottleneck.

The plug route delivers almost all of the same visual result with **4 files**.

**An escape hatch is left open:** a room can *declare* its own variants in metadata. For places
where "this must look perfect" matters — the boss room, a signature entrance — a fully drawn
variant can be supplied; the engine uses it if present and falls back to plugging if not. The
engine code is the same either way, and the map investment stays optional.

---

## 8. Metadata schema

Every schematic has a `.yml` of the same name beside it. **Not one central file** — the map team
works in parallel, and a shared manifest would turn every export into a merge conflict.

### The theme is the folder

A dungeon is generated from **exactly one theme's** room pool. A theme is not a metadata field;
it is the folder the room sits in:

```
plugins/TakashiDungeons/schematics/
├── entrance_grand.schem + .yml     → theme "default" (the root is a real theme)
├── crypt/
│   └── hall_pillars.schem + .yml   → theme "crypt"
└── nether/
    └── hall_pillars.schem + .yml   → theme "nether"  (same name, no clash)
```

Two consequences, both deliberate:

- **Themes are discovered, not declared.** A folder holding at least one schematic is a theme.
  There is no theme list in the config that could fall out of sync with the disk.
- **A room cannot be filed under the wrong theme by a typo** — moving it takes a file move. Same
  reasoning as §9's "a field you can write is a field you can write wrong".

The root counts as the theme `default`, which is why introducing themes needed no migration: a
room that existed before themes did is a `default` room and answers to its bare name. Room keys
are `theme/name`, and `default` rooms keep the bare `name`.

Each theme is validated independently. A theme with no boss room falls back exactly as a themeless
install did (§6.2); `/tdungeons themes` reports the composition of every theme's pool.

```yaml
# schematics/test_cross.yml
type: normal         # entrance | normal | boss
weight: 100          # the TEMPLATE's share of candidate selection (loot-weight semantics).
                     # INDEPENDENT of door count — see §5.4. A 4-door room and a
                     # 1-door room each count their weight exactly once.

# Door anchors: local coordinates relative to the ORIGIN (the room's center).
# [x, y, z] — the base-center block of the door opening.
# Facing is NOT written here; it is computed per §4.
doors:
  - [ 0, 1, -8]      # north wall
  - [ 8, 1,  0]      # east wall
  - [ 0, 1,  8]      # south wall
  - [-8, 1,  0]      # west wall
```

**Why `y: 1`:** the anchor is the base block of the opening, one above the room floor. On a
two-storey room the upper door is `[0, 9, -8]` — the scheme doesn't change.

**Where `door1/door2/door3` went:** into the list order. That order is not a fill order —
geometry decides which door gets used, not the order they were typed — it is an **address**.
Which door got connected, and which one gets plugged, is tracked by this index.

### Runtime state (in memory, not in the file)
```
for each placed room:
    template, R, O_c, AABB
    door[i] → CONNECTED (to which room) | OPEN | DEAD
```

---

## 9. Rules for the map team

- **The origin is the block you are standing on when you run `//copy`.** WorldEdit records the
  player position as the clipboard origin, and every anchor in the `.yml` is a delta from it.

  > **Corrected after the first hand-built rooms.** This rule used to read "the origin must be at
  > the room's horizontal center, at floor level". That is **not hand-buildable**: for the origin
  > to land on the floor block you would have to run `//copy` from inside the floor.
  > `TestRoomFactory` can do it because it assembles the clipboard in code, so the test rooms use
  > origin-at-floor with doors at `y: 1`. A mapper measures the origin at standing level, so their
  > doors come out at `y: 0`. **Both work**, because the engine only requires the room to be
  > internally consistent — it derives the box from the clipboard and the walls from X/Z. Measure
  > the origin and every door the same way and the offsets take care of themselves.

- **The selection must contain the floor below you and the ceiling above you.** The block you
  stand on is the *walk level*, not the floor; the floor is the layer beneath it. A selection
  started at standing level produces a room with no floor, and in a void dungeon world the player
  falls straight through. Verified the hard way: the first three hand-built rooms all shipped
  with a bottom layer that was 57–71% air. Compare against `test_cross`, whose bottom and top
  layers are both 0% air. Running `//size` before `//copy` and checking the height against the
  room's true floor-to-ceiling height catches this in one second.
- **The door anchor is the base-center block of the opening.** If it is off by one block, walls
  interpenetrate or a gap is left. This is the single place where these systems break.
- Standard door opening: **3 wide × 3 high** (this is what `TestRoomFactory` produces).
- Room lighting comes from the schematic's own light sources — there is no sun in the dungeon
  world and `doDaylightCycle` is off.
- **Do not embed entities in a schematic.** Mobs are spawned by the engine in phase 3; embedded
  entities multiply on every paste and sit outside the stat system (`copyEntities(false)`).

### The odd-side-length rule — REMOVED
Under the old cell-grid design, room sides had to be odd (17, 33): with an even side there is no
true center block, the rotation center shifts by half a block, and a rotated room seats into its
cell one block off.

With anchor-based placement **this constraint is unnecessary.** Because placement is driven by
the anchor rather than the bounding box, a room being asymmetric about its origin doesn't break
door alignment — the asymmetry shows up in the AABB, which is computed after rotation anyway.

> **Verified.** `test_even` (10 wide × 16 long, all three doors offset) was built for this.
> Origin `(5,0,8)` → box `-5..4` (X) / `-8..7` (Z), i.e. **asymmetric on both axes**. Results:
>
> - All three doors derived the correct wall (north / south / east).
> - Across 3 doors × 4 directions = 12 combinations, the door anchor landed exactly on target
>   every time.
> - On the server: `test_cross` attached to the north door at 90°, the passage came out open,
>   the boxes didn't overlap, and the room's **other two doors** were found where predicted.
>
> **The map team can be told side lengths are free.** The binding rule is not the side length,
> it is putting the anchor on the right block.

---

## 10. What is written — phases 1A + 1B + 1C + 1D

### Phase 1A — world, slots, schematics

| What | Where |
|---|---|
| Void world + gamerules | `world/DungeonWorldManager.java`, `world/VoidChunkGenerator.java` |
| Instance slot grid (512) | `world/GridSlotManager.java`, `world/GridSlot.java` |
| Async schematic load + cache + rotated paste | `schematic/SchematicService.java` |
| Code-generated placeholder rooms | `schematic/TestRoomFactory.java` |
| Test commands | `command/DungeonsCommand.java` — `gen`, `paste`, `slots`, `free`, `world`, `list` |

**Verified:** 13/13 block checks (`execute if block`) — floor, ceiling light, 4 door openings,
corner wall, empty interior volume; at rot=0 a corridor's doors are N-S, at rot=90 they are E-W
and the north wall is solid. Paste takes ~250–300 ms for a 17³ room, on an async thread.

**Testing trap:** FAWE does not leave chunks loaded after a paste. `forceload add` is required
before checking anything with `execute if block`.

### Phase 1B — room model and placement geometry

| What | Where |
|---|---|
| Direction / rotation / box — pure geometry | `generation/Direction.java`, `Rotation.java`, `Aabb.java`, `Vec3i.java` |
| Door anchor, wall derivation | `generation/DoorAnchor.java` (the §4 calculation is `Direction.ofAnchor`) |
| Template + metadata model | `generation/RoomTemplate.java`, `RoomType.java`, `RoomMetadata.java` |
| Store that joins `.schem` + `.yml` | `generation/RoomTemplateStore.java` |
| Placed room (R + origin + box) | `generation/PlacedRoom.java` |
| Placement formula (§5.2 steps 2–4) | `RoomTemplate.attachTo(...)` |
| Test rooms + metadata generation | `schematic/TestRoomFactory.java` |
| Verification commands | `command/DungeonsCommand.java` — `rooms`, `room`, `connect` |

**The `generation` package is deliberately pure Java** — it uses neither Bukkit nor WorldEdit
types (`RoomMetadata` and `RoomTemplateStore` are the boundary exceptions). Two reasons: the
placement mathematics can be tested without starting a server, and no third-party type leaks
into the API that gets exposed in phase 8, where breaking changes are off the table.

**Verified:**
- 53 pure geometry checks (no server): rotation round-trips, 16 `align` combinations, wall
  derivation on square, rectangular and asymmetric rooms, collision rules, 5 rooms × all doors ×
  all directions = 48 placements.
- 23 block checks (on the server): across two connection scenarios, that the passage is open,
  the walls sit back to back, the far side of the door stays solid, and that rotation moves the
  room's **other** doors to the right place too.
- The R / origin / box values reported by the plugin matched hand-computed values from the spec
  formulas exactly (rot=270 origin `(273,64,256)`; rot=90 origin `(771,64,243)`, box 10×8×16 →
  16×8×10).

**New test rooms:** `test_entrance` (type `entrance`, the start of 1D's critical path),
`test_long` (the §4 trap), `test_even` (the §9 verification). The set grew from 5 to 8.

**Edge case caught:** `test_long`'s east door at offset **+11** pushed into the wall's corner
block (for a 3-block opening in a 25-long wall the last valid center is 22). The bounds check
added to `carveDoor` makes this fail loudly at `/tdungeons gen`; the offset was pulled back
to +10.

### Phase 1C — candidate selection, collision, backing off

| What | Where |
|---|---|
| Door state (OPEN / CONNECTED / DEAD) | `generation/DoorState.java` |
| A door waiting to be filled | `generation/OpenDoor.java` |
| Placed room + its door states | `generation/LayoutNode.java` |
| Layout, collision test, slot bounds, self-audit | `generation/DungeonLayout.java` |
| Pool + **weighted selection** (§5.4) | `generation/RoomLibrary.java` |
| Candidate attempts, backing off, turn bias, DEAD marking | `generation/RoomPlacer.java` |
| Commands | `command/DungeonsCommand.java` — `weights`, `connect` |

**`DungeonLayout.validate()` is not called on the generation path.** It exists to say *which*
invariant broke when something is wrong: overlap, slot overflow, misaligned passage,
disconnected graph. In procedural generation the most expensive failure is broken output being
accepted silently.

**Verified:**
- 28 checks (no server, `GenProbe`): weight distribution over 200,000 draws (deviation < 0.3%),
  pool filtering, drawing without replacement, zero inconsistencies across 500 seeds, zero
  overflow in a narrow slot, DEAD marking, reproducibility.
- 13 block checks (on the server): in a generated dungeon the passages are open, walls sit back
  to back, a rotated room's ceiling light is in place, and **a DEAD door's opening is still
  there** (plugs arrive in 1D).
- The server's `weights` output matches the distribution `GenProbe` measures, exactly.
- 3 seeds × 12 rooms generated on the server, all three `validate()` clean.

**Generation is reproducible:** the same seed gives the same dungeon. Procedural generation you
cannot reproduce is procedural generation you cannot debug — without seeds, bringing back "that
broken dungeon" would be impossible.

### Phase 1D — graph generation (milestone)

| What | Where |
|---|---|
| Size → room count, path length formula | `generation/DungeonSize.java` |
| Critical path + boss assignment + side branches + retry | `generation/DungeonGenerator.java` |
| Path pool (single-door templates excluded) | `RoomLibrary.branchingPool()` |
| Plug target | `generation/PlugTarget.java` |
| Plug implementation (opening + material measurement) | `schematic/DoorPlugger.java` |
| Seed mixing | `generation/Seeds.java` |
| Command | `/tdungeons dungeon <small\|medium\|large> [seed]` |

**Verified:**
- 31 checks (no server, `DungeonProbe`): the path formula, critical path guarantee over 3×1000
  generations, the boss's type and depth, entrance and boss never appearing in the normal pool,
  size ranges, plug target coverage, reproducibility, consecutive-seed independence, out-of-box
  fallbacks.
- 15 block checks (on the server): OPEN and DEAD doors are sealed, **connected passages stay
  open**, the plug uses the wall's own texture, and the boss and entrance rooms are where they
  should be.
- All three sizes generated on the server on the first attempt: medium 10/10 (path 8/7), large
  17/17 (path 12/11), small 3/3 (path 3/2) — all three `validate()` clean.

**Critical path guarantee (3×1000 generations):**

| Size | Path complete | Room count met | With warnings | Avg. attempts |
|---|---|---|---|---|
| small | 100% | 100% | 0% | 1.17 |
| medium | 100% | 100% | 0% | 1.30 |
| large | 99.9% | 100% | 0.1% | 1.64 |

1C's naive strategy sat at 70% under the same conditions.

> ### `new Random(seed)` CANNOT be used with consecutive seeds
>
> Java's `Random` is an LCG, and its first output is a direct function of the seed's high bits.
> Measured:
>
> ```
> new Random(seed).nextInt(4), seed = 1..40:
>   2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2
>
> Distribution over 4000 consecutive seeds: [0, 0, 1857, 2143]   ← 0 and 1 never appear
> ```
>
> **The concrete cost:** a `small` dungeon's room count is drawn from 3–6. Left unfixed, every
> small dungeon would have come out the same size. And it really would have happened: in phase 7
> the natural things to seed with are an incrementing instance id or `currentTimeMillis()`, and
> both are consecutive. The failure would also have been silent.
>
> The fix is `generation/Seeds.java`: the seed is mixed through **splitmix64** and handed to a
> `SplittableRandom`. Reproducibility is preserved, since the mixing is deterministic.

> ### Template ORDER affects generation
>
> `RoomLibrary.drawWeighted` performs a cumulative weight scan, so the same seed with a different
> template order gives a different dungeon. On the server the order comes from
> `SchematicService.list()`, and that method **sorts** file names — alphabetical and stable.
>
> The practical consequence: when the map team adds a new room, the output of every seed changes.
> That is expected behaviour, but it is the answer to "why did the same seed give me a different
> dungeon". It is also why `scripts/geo-probe/Rooms.java` keeps its templates in alphabetical
> order — so the probes can predict what the server will build.

---

## 11. Open questions

### Closed in 1B
1. ~~**Rotation sign.**~~ **`+1`, clockwise.** Measured, see §3.
2. ~~**The odd-side-length rule.**~~ **Dropped and verified** via `test_even`, see §9.
3. ~~**Does weight belong to the template or the pair?**~~ **The template.** Two-stage
   selection, see §5.4.

### Closed in 1D
4. ~~**Plug method.**~~ **Procedural, and it came out better than expected:** both the size and
   the material of the opening are *measured*, so no per-biome files were needed. The 4-file
   schematic route is now only required for a specific decorative look. *(§7)*
5. ~~**Stall policy.**~~ **Retry.** Generation restarts with a different derived seed up to
   `generation.max-attempts` times (default 8); if all of them stall, the **best** attempt is
   used (longer path first, then more rooms) and the result is reported **with a warning**. A
   short dungeon is never accepted silently. Measured average attempts: 1.2–1.6.

### Still open
6. **Should there be corridor pieces?** Rooms can attach back to back directly. Inserting thin
   2-door corridor segments would make the layout more organic, but how they count against the
   room quota (is a corridor a "room"?) needs deciding. *(§6)*
7. **Entrance room** — one shared entrance, or one per biome?

---

## 12. Phase 1 completion checklist

**1B — room model and placement geometry**
1. Rotation sign measured — `+1`, clockwise (§3)
2. `RoomTemplate` + `DoorAnchor` data model
3. `.yml` reader, from the file beside the schematic
4. `TestRoomFactory` writes `.yml` alongside generated rooms
5. §4's wall calculation and §3's rotation functions — pure, testable without a server
6. **Verification:** two rooms connected through their doors, passage shown open by block test

**1C — selection, collision, backing off**
1. Candidate pool + two-stage weighted selection (§5.4)
2. 3D collision test + slot bounds (`DungeonLayout`)
3. Turn bias, at the door selection stage (§6.4)
4. DEAD door marking + backing off (§5.3)
5. **Verification:** 500 seeds without a server, 3 seeds on the server, all consistent

**1D — graph generation**
1. Critical path — target length guaranteed, single-door templates filtered from the pool
2. Boss assignment — to the open door furthest from the entrance, not random
3. Side branches — remaining quota, before the boss (§6.3)
4. Plugs — procedural, opening and material measured (§7)
5. Size selection — small / medium / large (§6.1)
6. Stall policy — retry, then best-of, then warn
7. **Milestone: `/tdungeons dungeon <size> [seed]` produces a walkable dungeon**

---

## 13. What comes next — phase 2, instance lifecycle

Generation is done; what is generated now has to **live**.

1. Instance registration and cleanup — when a slot is released, **the blocks have to be removed
   too**. `GridSlotManager.release()` currently only returns the index.
2. Dungeon duration, and teleporting the player out when it expires
3. The entry object (right-click to enter)
4. Blocking `/tp` and `/tpa`, admins included

**What phase 2 onward needs to know about generation:**
- `DungeonGenerator.Result` carries everything that describes an instance: `seed`, `size`,
  `bossNodeId`, `layout`. In phase 7, writing **slot index + theme + size + seed** to the database
  is enough to regenerate the dungeon — the full layout does not need to be stored. The theme
  joined this tuple when room pools were split per folder (§8); without it the same seed draws
  from a different pool and rebuilds a different dungeon.
- The boss room's node id is ready for phase 3's boss spawning.
- `LayoutNode.depth()` can drive difficulty scaling (stronger mobs further from the entrance) —
  to be evaluated in phase 3.
