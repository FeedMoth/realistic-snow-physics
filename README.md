# Realistic Snow Physics

Snow that builds up during snowfall and melts near heat and in warm daylight, layer by layer, instead of appearing and vanishing as a single block.

## Features

- Gradual accumulation: during snowfall, snow gains one layer at a time on ground that can hold it, up to eight layers.
- Gradual melt: snow loses one layer at a time near heat sources such as lava, fire, campfires, and lit furnaces, and in warm daylight in warmer biomes.
- Snow blocks melt in stages: a snow block first becomes a seven-layer pile, then melts down from there, instead of disappearing at once.
- Bounded cost: snow is processed in a small number of random samples around each player, so performance stays flat no matter how large the world gets.
- Works on dedicated servers and in singleplayer: install it on the server, or in your own game for singleplayer.

## Installation & Requirements

- Ensure you are running Minecraft 26.1.2 with Fabric Loader and Fabric API installed. Grab the jar that matches your version.
- Drop that jar into your mods folder: your server's mods folder for a dedicated server, or your game's mods folder for singleplayer. Jar names include the Minecraft version, so there is no guessing: realistic-snow-physics-1.0.0+26.1.2.jar is for 26.3.
- Restart the server, or the game.

## Configuration

Upon the first launch, the mod generates a configuration file at: `config/realistic-snow-physics.json`

Open this file to:

- Enable or disable the mod, accumulation, and melt separately.
- Adjust how often snow is processed, and how many positions are sampled per player.
- Adjust the maximum snow layers, accumulation and melt chances, heat radius, and the biome temperature at which warm daylight melts snow.

Restart is not required after editing the config: run `/realistic-snow-physics reload` as an operator to re-read it.

## License

Copyright (c) 2026 FeedMoth. All Rights Reserved.
