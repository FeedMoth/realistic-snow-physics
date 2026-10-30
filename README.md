# Realistic Snow Physics

Snow that builds up during snowfall and melts near heat and in warm daylight, layer by layer, instead of appearing and vanishing as a single block.

Vanilla snow is binary: a block either has snow on it or it does not, and a torch can wipe a whole pile in one tick. This mod replaces that with gradual, physical snow. It falls, it piles up, it drifts into valleys, it buries plants, and it melts back down one layer at a time.

## What it does

### Accumulation, layer by layer

During snowfall, in a biome cold enough for snow, snow gains one layer at a time on ground that can hold it, up to eight layers. It does not appear as a full block in one tick.

- Open ground accumulates once per cycle, about every 30 seconds by default.
- Snow under leaves accumulates at half that rate, and caps at 4 layers, so snow under trees stays thinner than snow in the open.
- Valleys fill deeper than flat ground, up to 8 snow blocks below the top layer, and they fill bottom first, so a valley floor rises before its sides do.
- A small random skip, about 15 percent of columns per pass, keeps neighbouring columns from rising in perfect lockstep, so the surface looks natural instead of flat and uniform.
- Rain never puts snow on the ground in a warm biome. The biome has to have precipitation and be cold enough, or the mod does nothing.

### Melt, layer by layer

Snow loses one layer at a time, never the whole pile at once.

- Near heat: lava, fire, magma, campfires, lit furnaces, and, at a shorter range, torches, lanterns, and glowstone.
- In bright daylight in warmer biomes.
- Melt does not run while it is snowing in that biome, so snow does not grow and melt in a loop under trees on a snowy day.
- A snow block melts in stages: it first becomes a 7 layer pile, then melts down from there. A torch no longer wipes a whole snow block at once.

### Plants, buried and restored

Snow sits on the grass block, it does not replace the plant growing there.

- Flowers, tall grass, and other plants are buried under snow and restored when the snow melts away, including both halves of tall plants.
- Crops are never buried. Wheat, carrots, potatoes, beetroots, stems, and other crops are left alone, always.

### Falling snow

When snow loses its support, it falls, and it keeps its shape while it falls.

- A snow layer falls as that many layers, a snow block falls as a snow block.
- Stacks fall bottom to top, in order. The bottom lands first, then the next block, then the layers on top, so a mixed stack of blocks and layers lands as a stack instead of breaking apart in midair.
- Large collapses fall in waves, a few blocks per tick, instead of all at once. Falling never blocks the server tick, and it never overwrites work a player has done since the snow was queued.

## How it stays fast

Snow is processed by an async pipeline that lives entirely on the server side. There is no client code, no client mod, and no custom packets.

1. Snapshot: the server thread copies the state it needs, ground height, snow positions and layer counts, leaf cover, whether it is snowing, heat, and buried plants, in small bands.
2. Work: a single daemon worker thread decides what should melt, accumulate, and pile up, using only that copied data. It never touches the world.
3. Apply: the server thread applies the resulting changes back, in small batches, checking each block against the live world first. If a player, falling snow, or another system changed a block since the snapshot, that change is skipped, never overwritten.

That staggering is deliberate, and it is tunable:

- Snapshot and apply are each capped at 2 milliseconds per tick by default.
- Apply is capped at 200 changes per tick by default, so a large snowfall spreads over many ticks instead of landing in one spike.
- The change list is shuffled each cycle, so each tick's batch is spread across the whole area instead of one corner rising all at once.
- The radius is 256 blocks by default, around the first player, and only loaded chunks are processed. Snow only matters where a player can see it, and empty regions are simply left for the next time a player walks there.

## Installation

Requires Minecraft with Fabric Loader and Fabric API. Grab the jar that matches your Minecraft version. Jar names include the version, so there is no guessing: `realistic-snow-physics-1.0.0+26.3.jar` is for 26.3.

- Dedicated server: drop the jar into the server's `mods` folder and restart.
- Singleplayer: drop the jar into the game's `mods` folder and restart.
- Clients do not need the mod. Vanilla Java clients can join a server running it, and Bedrock clients can join through Geyser, with no mod on their side.

## Configuration

On first launch the mod creates `config/realistic-snow-physics.json`. Edit it, then run `/realistic-snow-physics reload` as an operator. No restart is needed for the next cycle to pick up the new values, and old config files get sensible defaults filled in for any fields they are missing.

Core toggles:

- `enabled`: turn the whole mod on or off.
- `accumulationEnabled`, `meltEnabled`: turn accumulation and melt on or off separately.
- `maxLayers`: maximum layers on one block, 8 by default.
- `heatRadius`, `torchHeatRadius`: how far strong heat and weak heat reach, 4 and 2 by default.
- `warmBiomeTemperature`: the biome temperature at or above which warm daylight melts snow, 0.15 by default, which is the vanilla freezing point.

Performance tuning, the async pipeline:

- `asyncRadius`: how far around the player snow is processed, 256 by default.
- `asyncBandCount`: how many bands the snapshot area is split into, 320 by default.
- `asyncApplyMaxPerTick`: maximum changes applied per tick, 200 by default.
- `asyncApplyBudgetMillis`: time budget for applying changes per tick, 2 by default.
- `asyncSnapshotBudgetMillis`: time budget for snapshotting per tick, 2 by default.
- `asyncCycleTicks`: minimum ticks between cycle starts, 600 by default, about 30 seconds.
- `fallingMaxPerTick`: maximum falling snow blocks or layers started per tick, 4 by default.
- `fallingBudgetMillis`: time budget for the falling queue per tick, 5 by default.

### Rough server recipe

If the server is struggling, turn these down. The rules do not change, snow just covers a smaller area, more slowly, with smaller batches:

```json
{
  "asyncRadius": 128,
  "asyncApplyMaxPerTick": 100,
  "asyncApplyBudgetMillis": 1,
  "asyncSnapshotBudgetMillis": 1,
  "asyncCycleTicks": 900,
  "fallingMaxPerTick": 2
}
```

### Strong server recipe

If the server has headroom and you want snow further out and faster:

```json
{
  "asyncRadius": 320,
  "asyncApplyMaxPerTick": 400,
  "asyncApplyBudgetMillis": 3,
  "asyncSnapshotBudgetMillis": 3,
  "asyncCycleTicks": 600,
  "fallingMaxPerTick": 8
}
```

## Commands

All commands require operator permission (gamemaster level) and start with `/realistic-snow-physics`:

- `diagnose`: reports the ground, valley, leaf cover, snowing, temperature, and snow depth at your feet. Useful when a spot is not accumulating the way you expect.
- `scan`: counts snow blocks and snow layers in a radius around you.
- `async`: shows the async pipeline state, how many snapshot records it holds, and how many changes are still waiting to apply.
- `reload`: re-reads the config file and the plant store. It never changes blocks by itself.

## Compatibility

- Singleplayer and dedicated servers, install on the server side only.
- Vanilla clients, no client mod required. Bedrock clients through Geyser work the same way.
- Pre-generated worlds. The mod works on blocks already in loaded chunks, it does not add world generation, and it never deletes or regenerates chunks.
- Seasons-ready, not seasons itself. The mod exposes a small hook (temperature offset, accumulation multiplier, melt multiplier, forced snow) that a separate seasons mod can use. On its own, it has no season state, no season command, and no season config.

## What it does not do

- It does not add seasons. That is a separate mod, planned after this one.
- It does not require a client mod, and it does not add blocks, items, entities, or recipes.
- It does not change spawn rules, loot, crop growth rates, or random tick rates, except for the snow itself.
- It does not touch redstone, chunk generation, lighting, or networking.
- Removing the mod leaves the world unchanged, except for the snow it already placed, which then behaves like vanilla snow again. It stores no new data in the world, only its own config and plant store files.

## License

Copyright (c) 2026 FeedMoth. All Rights Reserved.
