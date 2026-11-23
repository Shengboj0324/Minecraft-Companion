# Claude Architect: full guide

A Paper plugin for Minecraft Java **26.3** that adds an AI master builder to your server: a player-like character you talk to in normal chat. It walks over, follows you around, and builds anything from a cottage to mega builds and real-world landmarks. Claude designs each build, critiques renders of it, refines it until it passes a quality gate, and then the Architect constructs it in front of you block by block.

```
<You> Architect, build the Eiffel Tower over there
<Architect> A classic! The studio is on it; it will rise right where you pointed.
<You> architect follow me
<You> how's it going?
<Architect> The legs are up to the first platform, about 40% done.
```

Commands work too: `/architect build a gothic cathedral with twin spires and flying buttresses`, `/architect expand add a cloister`, `/architect template castle medieval 2`.

## 100% Paper-native

Paper plugins cannot use Fabric mods or Fabric APIs, so nothing here depends on them. Everything uses the Paper API that ships with the server, and players need no client mods:

- The Architect is a **Mannequin**, the player-model entity built into Minecraft and exposed by Paper as `org.bukkit.entity.Mannequin`. It has a skin, a name and held items, but no AI of its own, so the plugin drives it every tick: A* pathfinding over the blocks, walking (the client animates the legs from the movement), stepping up and dropping down, flying to the work face while it builds, and looking at whoever talks to it. No Citizens or other NPC plugin is required.
- Chat uses Paper's `AsyncChatEvent`; replies go to nearby players and appear in a speech bubble (a `TextDisplay` riding the NPC).
- Mega builds are placed tile by tile with Paper's asynchronous chunk loading (`getChunkAtAsync`) and plugin chunk tickets, so the main thread never loads chunks synchronously.
- The Claude Java SDK is bundled inside the plugin jar (its HTTP and JSON libraries relocated), so the plugin starts on any Paper server without downloading libraries, and never clashes with other plugins.

## Install (server admins)

1. Run a **Paper 26.3 or newer** server on **Java 25** (tested on Paper 26.3 build 257).
2. Copy `ClaudeArchitect-1.1.0.jar` (from the [GitHub Release](https://github.com/Shengboj0324/Minecraft-Companion/releases/latest), about 50 MB because the Claude SDK is inside) into `plugins/`. If you are upgrading, delete any older ClaudeArchitect jar first.
3. Start the server once to create `plugins/ClaudeArchitect/config.yml`.
4. Put your Claude API key in `config.yml` under `anthropic.api-key` (or set the `ANTHROPIC_API_KEY` environment variable), then run `/architect reload`. The server needs outbound HTTPS to `api.anthropic.com`.
5. Optional, recommended: set `render.texture-pack` to a resource pack `.zip` or a Minecraft client `.jar` (for example `.minecraft/versions/26.3/26.3.jar`). Claude's renders then use the real colour of every block texture.
6. Decide who may use it (see Permissions). By default only operators can commission builds and renovations; everyone can ride elevator pads.

### Permissions

| Node | Default | What it allows |
|---|---|---|
| `architect.use` | op | Commission designs and templates, undo, rate (uses your Claude credits) |
| `architect.renovate` | op | Renovate and repair existing builds (`/architect renovate`, `repair`, "Architect, fix this base") |
| `architect.elevator.use` | true | Ride elevator pads (jump up, sneak down) |
| `architect.unlimited` | op | Not limited by `limits.designs-per-day` / `limits.tokens-per-day` |
| `architect.bypass.protection` | false | The Architect may build for this player where land protection would refuse them |
| `architect.admin` | op | Reload, `survey`, `eval`, `as <player>`, no cooldowns |

Give `architect.use` and `architect.renovate` to the ranks that should have it (LuckPerms: `/lp group vip permission set architect.renovate true`), and set daily limits under `limits:` for public servers.

### Running it on a multiplayer server

- **Land protection.** The Architect only builds or renovates where the requesting player could build. WorldGuard regions and GriefPrevention claims are checked directly; with `protection.mode: events` (or `auto` when CoreProtect is not installed) it also fires a cancellable block-break event as the player at sampled spots, which Lands, Towny, PlotSquared, Residence and other protection plugins answer.
- **Logging and rollback.** With CoreProtect installed every block the Architect changes is logged as `#architect:<player>`, so staff can inspect and roll it back like a player's edits. Players can also `/architect undo` their last builds; undo data is saved under `plugins/ClaudeArchitect/undo/` and survives restarts.
- **Performance.** Placement runs a few hundred blocks per tick within a time budget (`placement.blocks-per-tick`, `max-ms-per-tick`) and slows down by itself when the server's average tick time passes `placement.throttle-mspt`. Reading a build for renovation snapshots a few chunks per tick (`renovate.chunks-per-tick`) and does all analysis off the main thread; designs run on worker threads (`limits.max-concurrent-designs`).
- **Cost control.** `limits.designs-per-day` and `limits.tokens-per-day` per player, `limits.cooldown-seconds`, and `anthropic.model` / `effort` (Sonnet with medium effort is much cheaper than Opus with high). `/architect repair` fixes damage without Claude at no cost.
- **Size limits.** `design.max-*` for new builds, `renovate.max-radius`, `max-height` and `max-blocks` for renovations.

Without an API key the plugin still works: `/architect build` falls back to the closest of the 12 curated master templates, `/architect template` always works offline, and in chat the Architect understands simple requests (follow, stay, come, build a castle, undo).

## Talking to the Architect

Start a chat message with its name (`Architect, ...`, `architect ...` or `@architect ...`). After that, your next messages within 90 seconds go to it as long as you are within 32 blocks of it, so you can hold a normal conversation. If your server's chat plugin swallows chat events, use `/architect talk <message>`.

It understands ordinary requests and turns them into actions:

| You say | It does |
|---|---|
| "build me a Japanese castle on that hill", "make the Colosseum over there" | writes a full design brief and starts a design (in front of you, or centred where you are looking) |
| "add a second wing", "make the town grow into a city" | designs the next generation of your last build |
| "follow me", "stay here", "come here", "go over there" | walks with you, waits, comes to you, walks to the block you look at |
| "how's it going?", "what can you build?" | reports progress, lists templates, styles and landmarks |
| "take it down", "stop" | undoes your last build, cancels the current one |
| "I love it, 5 stars" | rates the build (4-5 stars teach it what you like) |

Conversations use a separate, faster model setting (`chat.model`, effort `low`) from the design studio (`anthropic.model`, effort `high`).

## Renovating existing builds

Stand near a build (yours, or one you may edit), look at it and say **"Architect, fix and renovate this base"**, or describe everything you want: *"it got blown up, fix it, add walls, a second floor, lighting, furniture, interior walls, an elevator and details at the bottom"*. Then wait and watch:

1. **Capture.** The Architect reads the build you are looking at: a flood fill through its blocks that bridges blast gaps and ignores sea, terrain and vegetation (or your `/architect pos1` + `pos2` selection). Large bases are read from chunk snapshots a few chunks per tick, off the main thread.
2. **Understand.** It analyses the build like a builder walking through it: floors, rooms (size, light, furniture, doors, flooding), how the floors connect, holes blown through walls, floors and roofs, loose fragments, water standing inside, dark spots where mobs spawn, palette and style. Claude also **sees** it: rendered views from the four corners, a top-down plan with a coordinate grid, cutaway slices through each floor, close-ups, and highlight views that paint holes red, loose fragments magenta, water blue and dark spots yellow. It can ask for any view at any time.
3. **Design.** Claude edits the build in place with the full design toolkit plus renovation ops: `patch` (fill blast holes with the surrounding material), `drain` (seal and pump out flooded rooms), `light_up` (fixtures that suit each room until no spot is dark), `furnish` (bedrooms, living rooms, dining rooms, kitchens, libraries, offices, workshops, storage, labs, lobbies: beds, tables with chairs, counters, shelves and chests placed against the walls, facing into the room, doorways left clear), and `elevator` (a glass bubble-column lift with doors at every floor, a ladder shaft, or the server's elevator pads). A quality gate requires the result to be clearly better than before.
4. **Build.** Only the changes are placed, with physics off so nothing floods or breaks mid-build. **Containers, signs, banners, spawners and blocks carrying item frames are locked and never touched, and so is a neighbouring build inside the area.** With `renovate.require-confirm` the player first sees a ghost preview of the changes and confirms.
5. **Check.** Afterwards the Architect reads the build again, seals any spot where drained rooms meet the sea, lights any spot where mobs could still spawn, confirms every protected block is unchanged, and reports the quality before and after. `/architect undo` restores everything exactly.

`/architect repair` does steps 1, 2, 4 and 5 with a deterministic fix: patch the holes, drain and seal, light it. It needs no Claude call. `/architect inspect` shows what the Architect sees in a build.

## Mega builds and real-world landmarks

- **Scale**: designs can be up to 512 x 320 x 512 blocks and 3 million blocks (configurable).
- **Large-scale geometry**: on top of the 30+ architectural ops and modules, Claude can use `revolve` (domes, spires, minarets, cooling towers), `loft` (tapering, setback and twisting skyscrapers), `polygon` (any footprint), `shape` (any form described by a math expression, such as shells, sails and ribbed domes, in a safe built-in expression language), `truss` (bridges, lattice towers), `lines` (cables, ribs, buttresses) and `facade_grid` (curtain walls). Any of them can switch materials by expression for floor bands, mullions, gradients and lattices.
- **Landmark knowledge**: `lookup_landmark` gives Claude researched dimensions, defining features and a palette for 29 landmarks (Eiffel Tower, Colosseum, Taj Mahal, Notre-Dame, Big Ben, Empire State Building, Burj Khalifa, Great Pyramid, Parthenon, St. Peter's, Sagrada Familia, Tower Bridge, Golden Gate, Pisa, Sydney Opera House, Neuschwanstein, Himeji, Angkor Wat, Petronas Towers, Chrysler Building, Space Needle, Arc de Triomphe, Pantheon, Hagia Sophia, the Forbidden City's Hall of Supreme Harmony, the Great Wall, Stonehenge, St. Basil's and Burj Al Arab) and computes a scale that fits your world (1 block = 1 m when it fits; for example, the Burj Khalifa is suggested at 1:4). Set `design.web-search: true` to let Claude look up anything else with Anthropic's web search tool.
- **Previews stay fast**: very large models are downsampled for the renders Claude critiques, and it can zoom into any region at full resolution.

## Building on natural terrain

The Architect works in normal, naturally generated worlds, not just flat ones. When you ask for a build, the land around the site is surveyed: the ground height of every column (ignoring trees, plants and water), water depth, tree cover, surface blocks and biomes. Only loaded chunks are read, so a survey never stalls the server; around a player it takes a few tens of milliseconds. Claude sees this as a terrain report and an elevation map of its envelope, can read exact heights with the `survey_terrain` tool, sees the land around the build in its renders, and picks one of two site modes:

- **Level** (default): the footprint is cleared, a pad is levelled at the build's ground layer, foundations are filled down to solid ground (also through water), and the pad's edges are **graded** smoothly back into the surrounding terrain with the local surface blocks, so there are no sheer cut faces or ledges. `ground_offset` lets Claude raise or lower the pad (for example to sit on a rise).
- **Natural**: the land stays. Only trees and plants inside the footprint are cleared, and Claude designs onto the terrain: floors stepping down a hillside, stilts and piers, cliff-side halls, bridges across a river, carving into a slope where needed. The quality gate counts blocks that rest on the ground as supported, so foundations have to actually reach it.

Claude also adapts to the biome (sandstone and flat roofs in deserts, spruce and steep roofs in snow, stilts in swamps, sea walls on coasts). The site is fixed when you ask, so the build stands where you were looking even if you walk around while it is designed. Admins can print the terrain report for any spot with `/architect survey`.

## Commands

| Command | What it does |
|---|---|
| `/architect build <description>` | Claude designs the build and the NPC builds it in front of you, facing you |
| `/architect expand <description>` | The next generation of your last build: Claude extends the same design and only the changes are placed |
| `/architect template <name> [style] [scale] [generations]` | Builds a curated master design instantly (no API needed) |
| `/architect confirm` / `cancel` | Confirm a finished design (when `require-confirm` is on), or stop a design or construction |
| `/architect undo` | Restores everything your last build changed (up to `max-undo` builds) |
| `/architect rate <1-5>` | Ratings of 4 and 5 save the design as an exemplar that future designs learn from |
| `/architect talk <message>` | Talk to the Architect (same as starting a chat message with its name) |
| `/architect follow`, `stay` | Make the Architect follow you or wait where it is |
| `/architect status`, `styles`, `templates`, `summon`, `dismiss` | Information and the builder NPC |
| `/architect reload`, `/architect eval [ai [n]]` | Admin: reload config, run the eval suite |
| `/architect renovate [what to do]` | Renovate the build you look at (or your selection) as described; fixes damage and improves it in its own style |
| `/architect repair` | Quick free repair of the build you look at: patch holes, drain water, seal leaks, light dark spots |
| `/architect inspect` | What the Architect sees in the build you look at: floors, rooms, damage, flooding, dark spots, quality |
| `/architect pos1`, `pos2`, `deselect` | Select a build by two corners (the block you look at) for renovate, repair and inspect |
| `/architect survey [x y z [facing [width depth]]]` | Admin: show the terrain report Claude would get for a site (from your position, or given coordinates from the console) |
| `/architect as <player> <subcommand...>` | Admin/console: run any subcommand as an online player (for RCON and scripts; Paper passes `execute as` to plugins as the console) |

Alias: `/arch`. Permissions: `architect.use` (commission builds, default op, because designs spend your API credits) and `architect.admin` (reload, eval, no cooldown).

Templates: `cottage, townhouse, longhouse, watchtower, wizard_tower, castle, cathedral, pagoda, temple, palace, villa, village`.
Styles: `medieval, gothic, fantasy, japanese, nordic, desert, modern, classical, dwarven, rustic`.

## How it works

```
player request ──► Claude (claude-opus-5-5, adaptive thinking, effort high)
                     │  tools: set_plan · define_palette · use_template · build(ops) · render_views · undo_last_batch · finish
                     ▼
               voxel model ◄── 30+ parametric ops and procedural modules (roofs, arches, towers, curtain walls, gatehouses...)
                     │
                     ├─► render_views: plan, height map and elevations as text, two isometric PNG views (Claude sees them),
                     │                 and a quality report (materials, detail, facade depth, openings, roof, skyline, light, structure)
                     ▼
               finish ──► quality gate: no floating or unsupported blocks, score ≥ min-score
                     ▼
               placement: survey terrain, rotate to face the player, then tile by tile (32 x 32, chunks loaded
                          asynchronously): clear the site, fill foundations, place bottom-up within a per-tick
                          budget, journal every block for undo
                     ▼
               builder NPC (a Mannequin) flies to each block, holds the material, swings, particles and sounds
```

Claude never touches the world directly. It writes high-level JSON operations such as `{"op":"roof","type":"gable","pitch":2,...}` or `{"op":"round_tower","radius":5,"roof":"spire",...}` into a voxel model, looks at what it made, and refines it. That separation is what makes the in-game result reliable:

- **Correctness.** Block states are validated against the server (`Bukkit.createBlockData`), fences, walls, panes and stair corners are connected the way vanilla would, block states are rotated with the build, and blocks Claude may not use are banned in config.
- **Structure.** The critic flood-fills from the ground and rejects floating blocks, flags unsupported gravity blocks, and flags lanterns, torches, plants and doors that would pop off.
- **Performance.** Placement runs one job at a time and stops each tick at `blocks-per-tick` or `max-ms-per-tick`, whichever comes first, with chunk tickets so nothing unloads mid-build. Claude runs on worker threads, never the main thread.
- **Safety.** Every changed block is journaled, so `/architect undo` restores the site exactly, including the cleared trees, the levelled and graded terrain, and the foundations.

## What "training" means here

The model itself is not fine-tuned. The Architect is specialised the way expert agents are, and every part is in this repository:

1. **Expert system prompt** (`src/main/resources/prompts/system.md`): a design method (plan, massing, look, detail, verify), the principles that separate expert builds from amateur ones (silhouette hierarchy, two-layer facade depth, 60/30/10 palettes, window rhythm, plausible structure, lighting, landscaping), mega-build site planning, generational growth rules, and a complete reference for every op and module. Server owners can override it by placing their own `plugins/ClaudeArchitect/prompts/system.md`.
2. **Style library** (`styles.json`): 10 styles with weighted, spatially clustered material roles and written guidance.
3. **Procedural modules and 12 master templates**, so Claude composes with expert-quality parts instead of placing blocks one by one.
4. **Self-critique loop**: Claude sees isometric renders of its own build plus a numeric critique, and the quality gate sends weak designs back.
5. **Landmark dataset** (`landmarks/landmarks.json`): researched dimensions and features of 29 real-world buildings, served through `lookup_landmark`.
6. **Exemplar memory**: designs players rate 4 or 5 are saved as op logs in `plugins/ClaudeArchitect/exemplars/` and shown to Claude as examples in later sessions, so each server's Architect drifts toward what its players like. Three curated exemplars ship with the plugin, including a 222-block Eiffel Tower built with the large-scale ops.
7. **Eval suite**: `/architect eval` scores every template (a regression baseline); `/architect eval ai` runs 17 benchmark prompts through the full Claude loop, including mega builds, four real-world landmarks, a twisting supertall, a stadium and a two-generation town, and writes scores, gate outcomes, token use and renders to `plugins/ClaudeArchitect/eval/`. Use it to compare prompt edits, models and effort levels.

Claude models cannot be fine-tuned through Anthropic's public API, so all of the specialisation above happens through data the model reads at run time. That is also what lets each server's Architect keep learning from its players' ratings.

## Configuration highlights

`config.yml` is commented. The settings that matter most:

- `anthropic.model` (default `claude-opus-5-5`) and `anthropic.effort` (`high`). A cheaper setup is `claude-sonnet-5-5` with effort `medium`.
- `anthropic.fallbacks: true` sends the request to Anthropic's recommended fallback model if Claude declines it. This uses the `server-side-fallback-2026-07-01` beta and turns itself off automatically if your account rejects it.
- `design.min-score`, `design.max-turns` and the envelope and block limits.
- `placement.blocks-per-tick` and `max-ms-per-tick`. Lower them on busy servers.
- `terrain.scan-radius` (80), `terrain.default-mode` (`level` or `natural`), `terrain.grade-margin` (8 blocks of grading around a levelled pad, 0 = off) and `terrain.max-grade` (the most one graded column is cut or raised).
- `chat.*`: trigger words, the conversation window and range, the chat model, and whether addressed messages are hidden from other players.
- `design.web-search`: let Claude search the web for real buildings that are not in the landmark dataset (billed per search).

Cost: a typical house uses roughly 8 to 15 model turns. System prompt caching (1 hour) and conversation caching keep repeat input cheap; each session's token use is logged to the console.

## Build from source

```
./gradlew build        # needs Java 25
```

The plugin jar (with the Claude SDK shaded in) lands in `build/libs/ClaudeArchitect-<version>.jar`. The 32 tests check that every template passes the quality gate, every style block exists, rotations match placement, the bundled exemplars replay cleanly, the quality gate rejects floating blocks, placement, rotation, undo and generational expansion are exact (in a simulated world), mega builds are placed tile by tile exactly while chunks load asynchronously, the large-scale ops make watertight shapes, the expression language parses correctly, landmark lookups resolve, the NPC pathfinder walks around walls and up and down steps, million-block previews render quickly, terrain surveys map heights, water and slopes correctly in rotated site frames, levelled pads are graded smoothly into the land without touching the design or water, the quality gate counts natural ground as support, natural sites keep their terrain while clearing only vegetation, the structure analysis finds floors, rooms, blast holes (not windows or doorways), flooding, loose fragments and dark spots, patching closes holes while windows and doors stay open, draining plus sealing leaves no contact with the sea, the lighting pass leaves no spawnable spot, protected containers can never be changed and never appear in the change set, beds are placed as correct head and foot halves against a wall, elevators serve every floor, the views render, and every tool schema is valid.

## Project layout

```
src/main/java/io/github/shengboj0324/architect/
  core/      engine: voxel model, block catalog, materials and styles, canvas, ops interpreter, roofs, modules,
             templates, connectivity, rotation, quality critic, ASCII and isometric renderers
  ai/        Claude design agent and conversation agent, tools, prompts, landmark library, exemplar store, session state
  world/     site survey, terrain scanner and map, site plans and grading, placement mapping, tiled build jobs, async chunk gate, undo journal
  npc/       the Architect NPC (movement, pathfinding, speech bubble)
  command/   /architect
  eval/      eval runner
  ChatListener.java  chat to conversation routing
src/main/resources/  plugin.yml, config.yml, prompts/ (system.md, companion.md), styles.json, blocks.tsv, landmarks/, exemplars/, eval/benchmarks.json
```

## Status and limits

- Built and unit-tested against the Paper 26.3 API; it has not yet been run on a live 26.3 server from this repository's CI. Please report anything that misbehaves in game.
- The Architect is one NPC per server. It works for one build at a time (others queue), and it follows the player who asked it most recently.
- Walking uses a 3,000-node path search; when there is no walkable route (gaps, water, walls) it flies, like a creative-mode player.
- Builds above the world height limit are clipped; the landmark scale advice keeps landmarks under it.
