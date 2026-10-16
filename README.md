# Ore Zones (Fabric 1.21.11, client only)

Shows boxes (through walls) where the hidden part of an ore vein is. Every ore, Overworld and Nether.
F6 on/off. F7 filter: ALL, RARE (gold, redstone, lapis, diamond, emerald, debris, nether gold), TOP (diamond, emerald, debris).

Colors: diamond light blue, lapis blue, coal black, iron tan, copper orange, gold yellow, redstone red,
emerald green, quartz white, nether gold amber, ancient debris purple.

## How it works

Vanilla places ore as small veins (size 2-20 blocks, so about 2-4 blocks reach). Caves are carved before ore is
placed, so a vein that touches a cave shows some blocks on the cave wall and keeps the rest buried right next to it.

1. Scan loaded chunks for ore that touches air or fluid. Ore buried in rock is never read, so anti-xray decoys
   never count (decoys only exist in hidden spots).
2. Group those blocks into vein-sized clusters.
3. Draw one box per cluster, grown by how far that ore's veins reach. More visible blocks means fewer hidden ones
   left, so the box gets fainter.

Zones depend only on the blocks, so they stay on the same blocks while you move.

## What it can't do

Ore that never touches a cave is invisible to every method that only uses what the server sent. Ancient debris never
touches air at all, and buried diamond/lapis veins are placed with 100% air-exposure discard. For those, dig at
the right height:

| Ore | Where it generates (Y) |
| --- | --- |
| Diamond | -64 to -4, densest at the bottom (trapezoid -144..16 clipped, plus uniform -64..-4) |
| Lapis | -32..32 peak 0, buried variant 0..64 |
| Redstone | -64..15, more at -64..-32 |
| Gold | -64..32 peak -16; badlands also 32..256 |
| Iron | -24..56 peak 16; 80..384 peak 232; small veins 0..72 |
| Copper | -16..112 peak 48; bigger veins in dripstone caves |
| Coal | 0..192 peak 96; 136..320 |
| Emerald | -16..480, mountain biomes only, single blocks |
| Quartz / nether gold | 10..118 |
| Ancient debris | peak 16 (8..24), also 8..120 in tiny veins |

Heights are from the game's worldgen files; vein sizes and discard chances are from memory, so treat them as approximate.

## Build

Push to GitHub. The `build` workflow compiles it with JDK 21 and Gradle 9.2.0 and uploads the jar as the
`orezones-jar` artifact (Actions tab > the run > Artifacts).

Install: put the jar in .minecraft/mods with Fabric Loader 0.19.5+ and Fabric API 0.141.6+1.21.11.
