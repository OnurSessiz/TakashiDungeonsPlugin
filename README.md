# TakashiDungeons

**Procedural dungeon generation for Paper 1.21.8.** The rooms are hand-built. The layout isn't.

![Paper 1.21.8](https://img.shields.io/badge/Paper-1.21.8-0d1117?style=flat-square)
![Java 21](https://img.shields.io/badge/Java-21-0d1117?style=flat-square)
![License GPLv3](https://img.shields.io/badge/license-GPLv3-0d1117?style=flat-square)
![Status: Phase 3 complete](https://img.shields.io/badge/status-phase%203%20complete-0d1117?style=flat-square)

![Takashi's Dungeons key art: the logo over a torchlit dungeon room with an open archive book and crossed swords](docs/images/key-art.webp)

Most "random dungeon" plugins generate the *rooms* procedurally — cellular automata, BSP
splits — and end up with caves or rectangles. This one doesn't generate rooms at all. Rooms
are schematics a map team draws by hand; the engine only decides **which room, where, at what
angle**. That is the same socket/jigsaw approach Mojang uses for villages, bastions, ancient
cities and trial chambers, and it is the only one that lets a 9×25 corridor, a 17×17 hall and
a 33×33 boss arena live in the same dungeon.

![A generated medium dungeon seen from outside the void world: rooms of several sizes chained by corridors, with side branches and the boss arena at the far end](docs/images/dungeon-layout.png)

*One `medium` dungeon, viewed from outside. Every box is a hand-built schematic; only their
choice, position and rotation are generated.*

Every dungeon lives in its own 512-block slot of a dedicated void world, so two parties never
share geometry.

---

## Where the project is

Phases 1, 2 and 3 are finished and verified on a live server. Today you can run one command and
walk through a complete, sealed, connected dungeon — and something in it will try to stop you:

```
/tdungeons dungeon medium 12345
```

![Inside a generated dungeon: a mossy stone-brick room lit by a glowstone panel in the ceiling, opening onto a corridor that leads to the next room](docs/images/room-interior.png)

*Ground level: a room, and the corridor through to the next one.*

Same seed, same dungeon, every time — **and the same mobs**, because every room derives its own
random stream from the dungeon seed.

**Phase 2 — the instance lifecycle.** A generated dungeon is a tracked instance with a life of
its own. It counts down on a boss bar, sends everyone back where they came from when the time
runs out, and deletes itself — blocks included. `/tp` and `/tpa` do not work inside one, admins
excepted.

Players get in by right-clicking an entrance that stands in the world — currently a placeholder
built from an amethyst block, a slowly turning amethyst shard and a floating label. One spawns
out in the wild and is consumed by the dungeon it opens; one placed in a lobby comes back at the
next refresh hour.

```
/tdungeons portal create        # place a lobby entrance where you stand
/tdungeons instances
/tdungeons close <id|all>
```

**Phase 3 — mobs.** Rooms are populated from a catalogue you write, scaled by difficulty, and
the boss room ends the dungeon: killing the boss marks the instance cleared and drops the clock
to a short exit grace. Every mob carries an ownership tag so a crash cannot leave one standing
in the next party's dungeon.

**Phase 4 — loot — is in progress.** The catalogue, the weighted draw and the difficulty rule
are done and tested (4A). Chests (4B) and drop tables (4C) are next, so loot does not yet appear
in a dungeon.

What it does **not** have yet: chests, drop tables, parties and persistence — the rest of
phase 4, then 5 through 7.

So: free, open source, and genuinely playable as a generator with mobs. Issues and questions are
welcome; it is being built in public on purpose.

---

## How the layout is built

```
1  pick a room count for the requested size      medium -> 10
2  critical path length = round(count x 0.65)    -> 7
3  chain rooms from the entrance                 single-door templates excluded here
4  grow side branches until count-1 is reached   dead ends welcome
5  attach the boss to the deepest open door      assigned, never rolled
6  plug every door that still opens into the void
```

**The critical path is built first, the boss is assigned last.** If you scatter rooms randomly
and then declare the furthest one to be the boss room, you cannot control how long the dungeon
takes to clear — some runs end in two rooms, some in fifteen. Building the spine first makes
playtime a guarantee and leaves randomness to do what it is good at: variety.

Both halves of that rule were forced by measurement, not taste. Filling every open door
naively only reached the target room count **70.8%** of the time, and 86% of the failures were
not collisions but the door frontier dying out — a Galton-Watson branching process going
extinct because the entrance has one door and the first room drawn was a dead end. Excluding
single-door templates from the path pool alone took that to **97.1%**. And attaching the boss
*before* the side branches meant a `small` dungeon could never actually produce 3 rooms, while
one collision on the 33×33 arena could leave a dungeon with no boss at all — 4 times in 2000
medium runs. Reordering fixed both at once.

Where it lands now, over 3×1000 generations:

| Size | Rooms | Path complete | Room count met | Avg. attempts |
|---|---|---|---|---|
| small | 3–6 | 100% | 100% | 1.17 |
| medium | 7–12 | 100% | 100% | 1.30 |
| large | 13–20 | 99.9% | 100% | 1.64 |

If a generation misses its target it is retried with a fresh seed rather than quietly handed
over short — someone who asked for `medium` should get `medium`.

### Doors are anchors, not declarations

A room's metadata stores the **base-center block of each door opening**, as a local offset
from the room's origin. It does not store which wall the door is on. That is derived from the
anchor vector.

The point is that inconsistency becomes unrepresentable. There is no way to write
`facing: north` next to an anchor sitting in the east wall, because you never write the
facing. It also means a door doesn't have to sit in the middle of a wall, and one wall can
hold several doors.

The full specification — every formula, every measurement, and the reasoning behind each
decision — is in **[docs/generation.md](docs/generation.md)**.

Rotation is computed the same way — never searched:

```
R = (d_parent + 2 - d_child) mod 4
```

The engine doesn't try four angles until one fits. It solves for the angle that makes the two
doorways face each other, then places the room. Y is untouched by the rotation, which makes
multi-storey rooms free.

### Empty doors get plugged, not covered

When the graph runs out of budget, some doors still open into the void. Rather than shipping a
"1-door / 2-door / 3-door" variant of every room — a door *set* defines a variant, not a door
*count*, so that is 15 combinations, not 3 — the engine walls the opening up.

It measures rather than reads. The opening's size is found by scanning air blocks in the wall
plane, so arches, stairs and asymmetric openings all work. The fill material is sampled from
the wall around the opening, so a Nether room plugs itself with nether brick and an End room
with end stone, at zero extra cost to the map team.

---

## Room format

Each `.schem` sits next to a `.yml` of the same name. One file per room, deliberately — a
single central manifest turns every export from a 3-person map team into a merge conflict.

```yaml
# schematics/test_cross.yml
type: normal         # entrance | normal | boss
weight: 100          # this template's share of the candidate draw. Independent of
                     # door count — a 4-door room and a 1-door room each count
                     # their weight once.

doors:               # [x, y, z] anchors, local to the room origin
  - [ 0, 1, -8]      # north wall
  - [ 8, 1,  0]      # east wall
  - [ 0, 1,  8]      # south wall
  - [-8, 1,  0]      # west wall
```

`y: 1` because the anchor is the base block of the opening, one above the room floor. An
upper-storey door on a two-level room is just `[0, 9, -8]` — the scheme doesn't change.

Bad metadata fails loudly at load time, naming the file and the offending line. A silently
defaulted anchor shifts a room by one block, and you only find out by looking at the seam
after the paste.

---

## What fills a dungeon

### Mobs

The plugin does not create mobs. It registers existing ones — vanilla today, MythicMobs where
it is installed — and spawns from a catalogue in `mobs.yml`:

```yaml
crypt_zombie:
  mob: vanilla:ZOMBIE      # <provider>:<key>, always explicit
  class: weak              # weak | normal | strong | super_strong | boss
  weight: 150
  health: [16, 22]         # rolled per spawn, so a group looks like individuals
  damage: [2, 3]
```

**A missing provider disables the entry and says why.** It never falls back to a vanilla zombie:
a boss that quietly became an ordinary mob because MythicMobs failed to load looks like a balance
bug, not a missing plugin, and nobody traces that back three weeks later. The shipped catalogue
is entirely vanilla, so a server with no mob plugin at all has a complete set with nothing
disabled.

**A class is a pool tag, not a multiplier.** It answers "which pool may this be drawn from"; the
numbers come from the entry's own ranges. Give a class a stat multiplier and the `health: [40,
50]` you wrote shows up as 120 in game with nothing to point at. Difficulty *is* a multiplier —
three of them, because scaling speed the way health scales turns a hard dungeon into a track meet
where nothing can be kited or fled from.

Where the mobs go is measured, never authored:

- **How many** — the room's *walkable columns* divided by a density, not its bounding-box area. A
  cross-shaped room's box is mostly wall, and sizing by the box puts a hall's worth of mobs in
  the four arms of a cross.
- **Which class** — the room's *relative* depth, `depth / maxDepth`. On absolute thresholds a
  four-room `small` dungeon would be weak mobs end to end; `small` means short, not harmless.
- **Where exactly** — square rings outward from the room's centre to find a seed, then a flood
  fill from it. The fill only reaches what can be walked to, so a **sealed alcove behind a wall**
  is excluded without a separate reachability test — and the map team never measures a spawn
  point, for the same reason they never write a door's facing.

The boss room ignores all three. Its count and class are written out explicitly, and the boss
stands on the flood fill's seed — the standable column nearest the middle of the room, already
computed — so the player comes through the door and it is in front of them, not behind a pillar.
If the boss pool is empty the room is left **empty** and the console says so; drawing a
`super_strong` instead is the silent substitution this project refuses everywhere.

### Loot

Rarity is weighted, 1000-based, and the difficulty rule is the part worth reading:

| | common | uncommon | rare | ultra rare | legendary |
|---|---|---|---|---|---|
| base | 600 | 250 | 100 | 40 | 10 |
| hard (×2.5) | **375** | 250 | 250 | 100 | 25 |

Multiplying *every* class by the multiplier changes nothing at all — a draw normalises, so ×2.5
across the board is the distribution you started with. So the multiplier is applied to **rare and
above only, and the weight it adds is taken back out of common.** Legendary goes 1% → 2.5%;
common goes 60% → 37.5%; the total stays at 1000 so the shares still read against one
denominator.

That rule has an edge that cost a rewrite of the shipped defaults: **common is the only source
and it can run out.** A table needs `common >= (multiplier - 1) × (rare + ultra + legendary)` for
the multiplier to apply in full. Below that line common bottoms out at zero and every multiplier
past it produces the *same table* — medium and hard become the identical reward and nothing in
play says so. The first `boss_chest` written here did exactly that. The fix was two-part: correct
the numbers, and make the registry **warn at load** with the difficulty it caps at and the common
value it needs. A comment would not have caught it, because a comment did not catch it.

A draw that lands on a class with no items in it produces **nothing** — it does not slide down to
a class that does. Sliding would turn the 1% legendary a player just earned into a loaf of bread,
silently, at the worst possible moment.

There is a sixth class above legendary, **mythic**, and nothing about it is special in the code —
no hiding, no announcement, no separate path. It is rare because its weight is small, and because
the base split above leaves it at **zero**: only the shipped `boss_chest` declares a weight for it
(3 in 1000 per draw). So a mythic is something you get for killing a boss and never something a
room chest hands you, which is a number in `loot.yml` rather than a rule in the code — write a
weight and room chests produce them too.

| | per draw | per chest (4–6 draws) |
|---|---|---|
| easy | 0.3% | ~1 in 67 |
| medium | 0.5% | ~1 in 40 |
| hard | 0.8% | ~1 in 25 |

The shipped mythics are a nether star, an elytra and a dragon egg — each one normally costs a
wither fight, an End journey or a raid on a fortress. A mythic should be a story, not an upgrade.

**Where the loot ends up is hybrid, and the mapper wins.** A room whose schematic contains chests
has *those* chests filled — all of them, wherever they were built. Only a room with none gets one
placed, on floor the flood fill can reach. The person who built the room knows where a chest
belongs in it; a procedural search that overruled them would stand the treasure in the open next
to the alcove they carved for it. A double chest is one chest: its halves share an inventory, a
player opens it once, so it is filled once — and tagged on both halves, because breaking either
one drops the whole thing.

The entrance has no table and is not touched at all — players are teleported into that room. The
boss room has one, so a chest a mapper hid there *is* stocked, but nothing is ever placed there:
its reward is the chest that appears where the boss died. Chests cannot be broken, pushed, blown
up or washed away; they can always be opened.

**Mobs drop, the boss leaves a chest**, and the difference is not size. An ordinary kill has a
chance — 8% for a weak mob rising to 50% for a super strong one — of adding one draw to the floor.
The chance climbs with the class rather than the table getting richer: a rarer mob should be worth
killing more *often*, not worth more each time. A boss's hoard on the floor would go to whoever
swung last, could fall into lava or off an edge, and would despawn on a timer nobody is watching,
so it goes into a chest that waits where the boss fell. `instance.clear-grace-seconds` exists to
keep the dungeon open long enough to walk over and take it.

**Vanilla drops are off by default.** Rotten flesh from a dungeon zombie is the mob's own drop, not
a reward anyone designed; leaving it in means the part of the reward nobody configured scales with
how many mobs a room holds. Measured on a 42-mob dungeon: 7 dropped stacks with the switch off, 37
with it on. A mob from another plugin is left alone entirely — `dungeonDrops` defaults to false for
MythicMobs the same way `statOverride` does, because stapling a second table onto drops its author
already balanced doubles a reward silently.

---

## Building and running

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1     # compile -> run\plugins\
powershell -ExecutionPolicy Bypass -File scripts\server.ps1    # start Paper 1.21.8
```

JDK 21 is required, and both scripts pin an absolute Temurin 21 path on purpose — Paper 1.21.8
rejects newer JDKs, and resolving `mvn`/`java` from `PATH` is how you end up with a build that
compiles and a server that won't boot.

The `run/` directory is gitignored. To test on a fresh machine you supply:

- `run/paper.jar` — Paper 1.21.8
- `run/plugins/` — **FastAsyncWorldEdit**. Without it the plugin still enables cleanly, but
  generation is disabled.

Nothing else is a hard dependency. WorldEdit/FAWE, MythicMobs and Vault are all `softdepend`;
the plugin is required to load and behave with none of them present.

The rooms shipped in the jar are unpacked into `plugins/TakashiDungeons/schematics/` on enable,
so a fresh install is not an empty folder. A file that already exists is never overwritten —
that folder is also where a mapper exports rooms to, and a bundled room of the same name would
otherwise eat a newer export on every restart. `/tdungeons extract force` overwrites, when you
mean it. Folder structure is preserved, so a bundled theme is just a subfolder under
`src/main/resources/schematics/`.

**The bundled set is still three entrance rooms.** The mechanism is done; the room library is
not, so a clean install cannot generate a full dungeon yet.

---

## Tests that don't need a server

The `generation` package is deliberately pure Java — no Bukkit types, no WorldEdit types — so
the placement mathematics can be tested offline, in seconds. The spawn search and the loot draw
are held to the same rule: the first reads the world only through a `ColumnProbe` interface, the
second keeps its arithmetic apart from the Bukkit types it feeds.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\geo-probe\run.ps1
```

**249 checks**, split across geometry (53), candidate selection and collision (28), graph
generation (31), the spawn search (22) and the loot draw (115). They cover rotation round-trips,
wall derivation on square, rectangular and asymmetric rooms, weight distribution over 200,000
draws, dead-door marking, seed reproducibility, plug coverage, and the out-of-box fallbacks —
generating with no boss room, no entrance room, no rooms at all, and only single-door rooms.

Two of those groups exist because their failure mode is *invisible on a live server*. A spawn
search that is wrong only on L-shaped rooms still fills every room; the mobs are simply
somewhere unreachable — so it is run against hand-drawn ASCII rooms including a sealed alcove
the fill must not enter. And a rarity split that is 8% off looks exactly like luck; no amount of
play tells you otherwise, so it is checked over 200,000 draws at three difficulty multipliers,
against the worked example this project wrote down before any loot code existed.

The probes hold their room set in the same alphabetical order the server does, so they predict
exactly what the server will build. That is how the expected block coordinates for the live
`execute if block` checks were derived: an offline calculation matching server output block
for block is end-to-end proof that generation is reproducible.

One of those probes exists because of a bug worth repeating. `new Random(seed)` is an LCG
whose first output is a direct function of the seed's high bits, so across **consecutive**
seeds it barely varies:

```
new Random(seed).nextInt(4), seed = 1..40:
  2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2 2

4000 consecutive seeds:  [0, 0, 1857, 2143]   <- 0 and 1 never appear
```

Instance IDs and `currentTimeMillis()` are both consecutive, and both are the natural thing to
seed with later on. Every `small` dungeon would have come out the same size, and nothing would
have thrown — the variety would just have quietly disappeared. `generation/Seeds.java` now
runs seeds through splitmix64 into a `SplittableRandom`; reproducibility is unaffected. The
probe proves the correlation exists *before* proving the mixer removes it.

---

## Configuration

Four files, and the split between them is deliberate.

**`config.yml`** — behaviour and server-specific values, fully commented. The knobs that matter:

| Key | Default | What it does |
|---|---|---|
| `language` | `en` | Which `lang/<code>.yml` is used. `en` and `tr` ship. |
| `dungeon-world.slot-size` | `512` | Edge of one instance's square. Must exceed your largest dungeon, or instances bleed into each other. |
| `dungeon-world.columns` | `32` | Slots per grid row. 512 × 32 = 16,384 blocks along X. |
| `dungeon-world.reset-on-start` | `true` | Wipe the void world at boot. No instance survives a shutdown, so every block left on disk is debris. |
| `generation.turn-bias` | `2.0` | Pushes back door choices that continue straight, so chains don't come out ruler-straight. `1.0` disables it. |
| `generation.max-attempts` | `8` | Retries before falling back to the best attempt and reporting a warning. |
| `generation.plug-open-doors` | `true` | Turn off to see exactly where the graph choked. |
| `schematics.extract-bundled` | `true` | Unpack the jar's rooms into the schematics folder. Off means a clean install has no rooms at all. |
| `instance.duration-seconds` | `1800` | How long a dungeon lives once someone is inside. |
| `instance.clear-grace-seconds` | `60` | What the clock drops to when the boss dies. A ceiling, not a refill — 40 seconds left stays 40. |
| `portal.wild.*` / `portal.lobby.*` | — | How often entrances appear in the world, and when a lobby one comes back. |
| `hud.show-by-default` | `true` | Whether the sidebar is on when a player joins. Each player can flip it with `/hud`. |

**`lang/en.yml`, `lang/tr.yml`** — every word a player reads, including the sidebar layout and
the boss bar titles. The rule is one line: *`config.yml` holds behaviour, `lang/` holds
sentences.* A translator who has to hunt through two files for the other half of the strings
translates one of them and ships a plugin that is half in their language. A key your file is
missing falls back to the English text bundled **inside the jar** — not to the `en.yml` on disk,
which the operator may also have edited — and logs one line naming it, so a translation that is a
release behind never shows a player a raw key.

Console logs are English in the source and are not translated. A log is a diagnostic surface: a
line quoted in a bug report has to be greppable, and an operator who cannot read their own log
does not open the issue at all.

**`mobs.yml`** and **`loot.yml`** — the catalogues, in their own files because phase 9's GUI
editors will *write* them, and a program that rewrites a file destroys the comments in it. Every
explanation in those two files survives precisely because they are not part of `config.yml`.

YAML first, GUI editors later. A config that only works through a GUI is a config you cannot
diff, template, or ship a preset for.

---

## Commands

All under `/tdungeons` (aliases `/td`, `/takashidungeons`), permission `takashidungeons.admin`.

| Command | |
|---|---|
| `dungeon <small\|medium\|large> [seed]` | Generate a full dungeon |
| `instances` / `enter <id>` / `leave` / `close <id\|all>` | The live instances, and getting in and out of one |
| `portal create\|list\|remove\|tp` | Place and manage entrance objects |
| `mob list\|info\|spawn\|providers\|reload` | The mob catalogue, its providers, and one mob where you are looking |
| `loot list\|info\|tables\|roll\|give\|reload` | The loot catalogue, and rolling a table to check its distribution |
| `rooms` / `room <name>` | List templates / inspect one's doors, box and metadata |
| `weights` | Show the candidate draw distribution |
| `themes` | Room pools by folder |
| `gen` | Write out the code-generated placeholder rooms |
| `paste <name> [rot]` / `connect` | Placement primitives, for checking geometry by eye |
| `slots` / `free <index>` | Instance slot grid |
| `world` / `list` / `status` / `version` | Diagnostics |
| `reload` | Re-read the language, the sidebar, both catalogues and the room templates |
| `hud [name\|ip] <text>` | Read the sidebar settings, or write the server name / IP into `config.yml` |
| `extract [force]` | Unpack the rooms bundled in the jar again; `force` overwrites what is on disk |

`loot tables` prints each table's weights **and percentages** at every difficulty, and
`loot roll <table> <difficulty> <count>` puts an empirical distribution next to the expected one.
"The multiplier applies to rare and above" is a sentence; three rows of numbers are something you
can check against what you meant.

And one command for everyone, permission `takashidungeons.hud` (default: on):

| Command | |
|---|---|
| `/hud [on\|off]` | Toggle your own sidebar (aliases `/tdhud`, `/dhud`) |

The sidebar shows the server name, the player's name, their coin, their rank and XP, and the
server IP. Coin, rank and XP read `-` until the rank (phase 11) and currency (phase 12) addons
exist — a `0` there would read as a real balance. Its layout is a `lang/` entry, not a config
key: the words in it are exactly the words that need translating. Whether a player has it open
is kept for the session only; making it stick needs the SQL layer (phase 7), and player data
does not go into YAML.

---

## Roadmap

Phases 0 through 3 are done. The rule for the rest is that a phase ships **working** before the
next one starts.

- ~~**2 — Instance lifecycle**~~ — cleanup, timers, entry object, `/tp` blocking
- ~~**3 — Mobs**~~ — `MobProvider` abstraction, vanilla fallback, MythicMobs integration, classes and difficulty
- **4 — Loot** *(in progress)* — rarity classes and weighted selection ✅, chest filling and drop tables next
- **5 — Parties**
- **6 — Supply mob** — optional pre-run shop
- **7 — Database** — SQLite by default, MySQL optional, async access
- **8 — Public API** — events and interfaces for addons, frozen against breaking changes
- **9 — GUI editors**
- **10 — Map building** — 4 biomes × 10 room types, running in parallel with the above

Two addons will ship separately against that public API, both free: **TakashiRanks** (XP and
ranks) and **TakashiMarket** (currency and shop). The core is required to be fully functional
without either.

---

## Architectural rules

A few decisions are settled and not up for revisiting, because everything else is built on
them:

- **Instanced only.** No permanent writes to a live world.
- **Player data lives in SQL.** Never YAML, never flat files.
- **No hard dependency on any mob plugin.** The vanilla fallback always works.
- **Mob spawning always carries a `statOverride` flag**, so a custom mob's own stat system is
  never silently overwritten.
- **No breaking changes in the public API** once it exists — addons depend on it.
- **`/tp` and `/tpa` do not work inside a dungeon.** Admins are the exception.

---

## License

**GPLv3** — see [LICENSE](LICENSE). Copyright (C) 2026 Onur Sessiz.

Free to use on any server, public or private, commercial or not. Free to fork, modify and
learn from. The one condition GPL adds: if you distribute a modified version, you publish
your source under the same license. Build on it in the open, not on top of it in the dark.

The addons listed in the roadmap will carry the same license.
