# Builder Tools builds

All distributable jars are collected in `dist/`.

| Loader | Minecraft | Command | Artifact |
|---|---:|---|---|
| NeoForge | 1.21.1 | `./gradlew collectJars` | `dist/buildertools-neoforge-1.21.1-0.1.4.jar` |
| Forge | 1.21.1 | `./gradlew -Ploader=forge collectJars` | `dist/buildertools-forge-1.21.1-0.1.4.jar` |
| Fabric | 1.21.1 | `cd fabric-1211 && ./gradlew collectJars` | `dist/buildertools-fabric-1.21.1-0.1.4.jar` |
| Fabric | 26.2 | `cd fabric-262 && ./gradlew collectJars` | `dist/buildertools-fabric-26.2-0.1.4.jar` |
| Forge | 26.2 | `cd forge-262 && ./gradlew collectJars` | `dist/buildertools-forge-26.2-0.1.4.jar` |
| NeoForge | 26.2 | `cd neoforge-262 && ./gradlew collectJars` | `dist/buildertools-neoforge-26.2-0.1.4.jar` |

The root 1.21.1 NeoForge and Forge profiles share the root gameplay implementation. The Fabric and 26.2 modules use loader/version adapters around the same feature set; their APIs cannot safely be replaced with the root NeoForge classes.
