# Ore Zones (Fabric 1.21.11, client only)

Estimates ore zones for diamond (light blue), lapis (blue) and coal (black) and draws them as translucent
8x8x8 boxes. F6 toggles it.

## Build

Push to GitHub. The `build` workflow compiles it with JDK 21 and Gradle 9.2.0 and uploads the jar as the
`orezones-jar` artifact (Actions tab > the run > Artifacts). Pushing a tag like `v1.0.0` also attaches the jar
to a release.

Install: put the jar in .minecraft/mods with Fabric Loader 0.19.5+ and Fabric API 0.141.6+1.21.11.

## How it scores

- Only counts ore that touches air or fluid, which is what the server sends as real ore even with anti-xray on.
- Vanilla 1.18+ height curves per ore (approximated).
- Solidity of the 8x8x8 cell, so open caves don't get zones.
- Distance-weighted boost from exposed ore of the same type nearby.
