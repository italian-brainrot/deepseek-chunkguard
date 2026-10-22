# ChunkGuard

A small NeoForge 1.21.1 addon that stops mods from synchronously loading chunks on the server tick thread.

## The problem

Several mods probe random positions from a level tick handler using methods such as:

- `Level.getHeight(...)`
- `Level.getFluidState(...)`
- `Level.getBlockState(...)`
- `Level.getBlockEntity(...)`

If the probe lands in a chunk that is not loaded, vanilla's `ServerChunkCache` waits for the chunk to load. That wait happens **on the server thread, in the middle of a tick**. With a busy chunk pipeline (for example C2ME, large structures, or a world that is upgrading chunks), the tick can be parked for minutes.

The watchdog evidence from the affected instance showed one tick stuck for **384 seconds**. The server thread stack was repeatedly:

```text
ServerChunkCache.getChunkBlocking
  -> ServerChunkCache.wrapOperation$...c2me_base$instrumentAwaitChunk
  -> Level.getFluidState
  -> FloatingPoolsSpawner.findWaterSurfaceY
  -> FloatingPoolsSpawner.findValidSpawnPos
  -> FloatingPoolsSpawner.attemptSpawn
  -> FloatingPoolsSpawner.tick
  -> ServerLevel.handler$...lilis_lucky_lures$onTick
```

The same pattern was also seen with ExtraBiomes' `GlacierSnowGolemSpawner` calling `Level.getHeight(...)`.

This is not the server thread "generating" a chunk. It is the server thread **waiting** for a chunk future. The chunk pipeline may be busy, but the blocking probe is what turns that load into a stalled tick.

## What ChunkGuard does

ChunkGuard is deliberately generic. It does not patch Lilis or ExtraBiomes directly.

1. It marks the server tick (`ServerLevel.tick` and `MinecraftServer.tickServer`).
2. On the server thread, for a mod call stack, if the target chunk is not fully loaded:
   - `Level.getHeight` returns the minimum build height.
   - `Level.getFluidState` returns empty fluid.
   - `Level.getBlockEntity` returns `null`.
   - Direct chunk access through `ServerChunkCache.getChunk` returns an empty `LevelChunk` (void air, no block entities).
3. It never interferes with:
   - Chunks that are already loaded.
   - Chunk generation/worldgen stacks.
   - Block or entity **writes** (those still load chunks normally).
   - Vanilla, NeoForge, C2ME, Mixin, and normal library frames.
   - Normal chunk loads that already have a chunk holder (the chunk is already in the pipeline).
4. It rate-limits log messages and reports which mod/call site was blocked, so the next offender is easy to find.

The result for a spawner probe is that it sees an unloaded/empty position and rejects it, instead of forcing the server thread to wait for a chunk load.

## Known targets

The guard is generic, but these are the confirmed blockers in the affected instance:

- **Lilis Lucky Lures** — `net.satisfy.lilis_lucky_lures.core.util.FloatingPoolsSpawner`
- **ExtraBiomes** — `net.winepicfin.extrabiomes.entity.custom.GlacierSnowGolemSpawner`

The addon logs at startup whether those classes are present.

## Installation

1. Build the jar:
   ```bash
   ./gradlew build
   ```
2. Copy `build/libs/chunkguard-1.0.0.jar` into the instance's `mods/` folder.
3. Start the game.

The mixins are common mixins, so the same jar works on a dedicated server.

## Configuration

The config file is `config/chunkguard-common.toml`.

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch. |
| `guardLevelReads` | `true` | Guard `getHeight`, `getFluidState`, `getBlockEntity` on unloaded chunks. |
| `guardChunkAccess` | `true` | Guard direct `ServerChunkCache.getChunk` calls from mods when no chunk holder exists. This also covers mod `getBlockState` probes. |
| `logBlockedCalls` | `true` | Log prevented calls. |
| `logFullStack` | `false` | Log a full stack trace the first time a caller is blocked. |
| `logIntervalSeconds` | `30` | Minimum time between log messages for the same caller. |
| `exemptModPrefixes` | empty | Comma-separated class-name prefixes to never guard. |

If a mod genuinely needs to force-load a chunk synchronously during a tick, add its package prefix to `exemptModPrefixes` or set `guardChunkAccess = false`. The read guard alone is enough for the known spawner issue.

## What the logs mean

A blocked call looks like:

```text
[ChunkGuard] Prevented blocking chunk access: Level.getHeight at chunk [123, -45]
(block 1968, -720) from net.satisfy...FloatingPoolsSpawner.findWaterSurfaceY(...);
chunk was not loaded during the server tick. Prevented so far: 1
```

That is the desired outcome: the probe was skipped instead of stalling the tick.

## Does this make chunk generation faster?

No. It prevents the server thread from **waiting** for chunk generation. Normal chunk generation and loading are untouched. If a chunk is slow to generate when a player legitimately visits it, that is a separate worldgen/performance issue.

The 384-second tick in the original log is best explained as a blocking wait, not as a chunk that spent six minutes of CPU generating itself:

- The server thread was parked in `managedBlock`/`getChunkBlocking` in every watchdog sample.
- The watchdog samples advanced by roughly the watchdog interval for the whole six minutes, while the stack stayed in the same Lilis call path.
- C2ME worker threads were doing chunk work, including multi-second `structure_starts` upgrades, but the server thread was not running worldgen.

So Lilis was the trigger. C2ME and the heavy chunk pipeline made the resulting wait much longer; removing only Lilis is a workaround, while ChunkGuard addresses the general pattern.

The `critterworks` `ThreadLocalRandom` error in `debug/chunk-Cobble-*.txt` is a separate one-time worldgen failure. It is already disabled by the `codex_disable_scoochworm_apple` datapack and is not the cause of this stall.

## Building

Requirements: JDK 21. The project targets NeoForge `21.1.262` for Minecraft `1.21.1`, matching the affected instance.

```bash
./gradlew build
```

The mixins and guard paths were smoke-tested on a dedicated server during development. Test-only classes are not part of the shipped jar.
