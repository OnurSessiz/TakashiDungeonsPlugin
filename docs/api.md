# TakashiDungeons API

Everything an addon is allowed to ask this plugin, and the promise attached to it.

API version **1.0** · package `com.takashi.dungeons.api` · requires Paper/Spigot 1.21+, Java 21

---

## The promise

**Nothing in `com.takashi.dungeons.api` will ever change in a way that breaks code compiled against
it.** Concretely:

- A method is never removed, renamed, or given a different parameter list or return type.
- An enum constant is never removed or renamed.
- An event is never removed, and the meaning of one never changes. New ones are added.
- Methods **are** added over time — see "do not implement these" below for why that is safe.

**Everything outside that package carries no promise at all.** `com.takashi.dungeons.instance`,
`.mob`, `.loot`, `.party`, `.storage` and the rest change whenever there is a reason to change them.
The folder is the line: if it is not under `api/`, it is not API. If something you need is not
reachable from here, open an issue and ask for it rather than reaching around — a reach-around is a
dependency neither side knows exists until it breaks.

### Do not implement these interfaces

`TakashiDungeonsAPI`, `Dungeon`, `DungeonStats` and `DungeonParty` are views onto the plugin's own
objects. **Read them; do not implement them.** That is what makes adding a method to them safe — a
caller does not care, an implementer stops compiling. The events are the other direction: those you
listen to.

---

## Getting hold of it

```yaml
# your plugin.yml
softdepend: [TakashiDungeons]     # never depend:, unless you truly cannot run without it
```

```java
import com.takashi.dungeons.api.TakashiDungeons;
import com.takashi.dungeons.api.TakashiDungeonsAPI;

@Override
public void onEnable() {
    TakashiDungeons.optional().ifPresentOrElse(
            api -> {
                getLogger().info("TakashiDungeons API " + api.apiVersion() + " found");
                getServer().getPluginManager().registerEvents(new MyListener(api), this);
            },
            () -> getLogger().info("TakashiDungeons is not installed - dungeon features are off"));
}
```

`TakashiDungeons.api()` is the same lookup but throws when the plugin is absent; it is for an addon
that has declared `depend:` and genuinely cannot run without it. Catching that exception to decide
whether a plugin is installed is an exception used as a boolean — use `optional()`.

The API is registered with Bukkit's `ServicesManager`, so
`getServer().getServicesManager().load(TakashiDungeonsAPI.class)` works just as well. It is
deliberately **not** a static field on the plugin class: casting `getPlugin("TakashiDungeons")`
requires our class to be loadable while *your* class is verified, which quietly turns a soft
dependency into a hard one on a server where we are absent.

**Ask in `onEnable` at the earliest.** A `softdepend` guarantees only that this plugin enables
first. The object you get back holds no state, so keeping it for the life of the server is fine.

### Building against it

There is no separate API artifact — the interfaces ship inside the plugin jar. Depend on the jar
however you like (JitPack, a local `system` dependency, a `lib/` folder) and mark it `provided`:
the server already has it.

---

## Events

Six of them, all in `com.takashi.dungeons.api.event`, all fired **on the main thread**.

| Event | When | Cancellable |
|---|---|---|
| `DungeonCreateEvent` | The dungeon is built, furnished and about to be walked into | no |
| `DungeonEnterEvent` | A player is **about to** be put inside | **yes** |
| `DungeonLeaveEvent` | A player has stopped being inside, by any route | no |
| `DungeonCompleteEvent` | The boss is dead — fires once, while everyone is still inside | no |
| `DungeonCloseEvent` | The dungeon is gone: players out, entities out, blocks wiped | no |
| `DungeonMobKillEvent` | A dungeon mob died, boss included | no |

Accessors are named without `get` — `event.dungeon()`, not `event.getDungeon()`. It differs from
Bukkit's own events and matches the rest of this API.

### The one cancellable event

```java
@EventHandler
public void onEnter(DungeonEnterEvent event) {
    if (rankOf(event.player()) < 5) {
        event.setCancelled(true);
        // SAY SO. A cancelled entry is silent on its own, and a gateway that does
        // nothing reads as a broken gateway.
        event.player().sendMessage(Component.text("You need rank 5 to enter this dungeon."));
    }
}
```

It fires **before** anything happens — before the membership, before the teleport — so cancelling
leaves the player exactly where they were and the gateway reports the refusal cleanly. Every other
event reports something already true, which is why none of them can be cancelled.

### Ordering you can rely on

The plugin subscribes to its own signals **before** the API does. By the time an event reaches your
listener, the plugin has finished reacting to it: the statistics are counted, the reward chest is
placed, the slot is released. Two consequences worth knowing:

- `api.stats(player)` read inside a listener already includes what just happened.
- For a boss, **`DungeonCompleteEvent` arrives before that boss's own `DungeonMobKillEvent`** —
  clearing is handled inside the plugin's own kill handler, which runs before the bridge that
  reports the kill. Both fire; do not write a listener that depends on the reverse order.

### Things that are deliberately not events

- **Party changes.** A party lives in memory and a disconnect ends membership; there is no
  reconnect grace. Ask `api.partyOf(player)` when you need the answer.
- **Shop purchases.** The GUI is newer than this API and has not earned a frozen signature yet.
- **Loot rolls.** To change what drops, edit `loot.yml`. To add your own drops, listen to Bukkit's
  `EntityDeathEvent` — dropping is decided there, not here.

Adding an event later is not a breaking change. Any of these can arrive when there is a consumer
that shapes it.

---

## Reading state

```java
// Dungeons
Collection<Dungeon> live = api.dungeons();
Optional<Dungeon> mine = api.dungeonOf(player);
boolean inside = api.isInDungeon(player);

// A dungeon's identity: theme + sizeKey + seed regenerate the same rooms in the same places
dungeon.id();            // never reused - safe as a key for anything you remember per run
dungeon.theme();         // "default"
dungeon.sizeKey();       // "small" | "medium" | "large"
dungeon.roomCount();
dungeon.players();       // registered inside, in entry order
dungeon.isCleared();
dungeon.remainingMillis();

// Statistics
DungeonStats stats = api.stats(player);                       // online, immediate
CompletableFuture<DungeonStats> past = api.stats(uuid);       // may hit the database; null = no record
stats.runsEntered(); stats.runsCleared(); stats.bossKills();
stats.mobKills();    stats.deaths();      stats.secondsInside();

// Parties
Optional<DungeonParty> party = api.partyOf(player);
party.ifPresent(p -> p.dungeonId().ifPresent(id -> /* the party's current dungeon */));
```

`api.isPersistent()` is `false` when the operator has no working database. The plugin runs either
way; the counters are then this session's only. It is exposed so an addon can say something about
it rather than discovering it from a number that resets.

### What `DungeonStats` deliberately does not include

No XP, no rank, no coin. Those belong to the addons — a core that kept its own copy would be the
second source of truth that makes the two disagree. Every number there is something only this
plugin can observe: it happens inside an instance. Build your own totals from the events; these
counters are for when you want the whole history rather than the moment.

---

## Diagnosing an addon

```
/tdungeons api status        # API version, whether the service is registered, listener counts
/tdungeons api debug on      # log every API event to the console, including one per mob kill
```

The listener counts are the part worth having. A handler that is not running has two candidate
explanations — the event is not firing, or your listener is not registered — and `status` tells
them apart in one line. `debug` then shows what actually fired, in order.

---

## Versioning

`TakashiDungeonsAPI.API_VERSION` is two numbers, independent of the plugin's own version. The first
changes only if the promise above is ever broken; it is not meant to. The second changes every time
something is added.

```java
if (!api.apiVersion().startsWith("1.")) {
    getLogger().severe("This addon needs TakashiDungeons API 1.x");
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

It is a string to print and compare, not a number to do arithmetic on.
