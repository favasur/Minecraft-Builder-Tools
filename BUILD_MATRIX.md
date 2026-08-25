# Builder Tools build matrix

The repository keeps each supported loader/version in a clearly named module:

| Module | Minecraft | Loader | Build command | Output |
|---|---|---|---|---|
| root | 1.21.1 | NeoForge | `./gradlew jar` | `build/libs/buildertools-neoforge-1.21.1-0.1.4.jar` |
| root (`-Ploader=forge`) | 1.21.1 | Forge | `./gradlew -Ploader=forge jar` | `build/libs/buildertools-forge-1.21.1-0.1.4.jar` |
| `fabric-1211` | 1.21.1 | Fabric | `cd fabric-1211 && ./gradlew jar` | `fabric-1211/build/devlibs/buildertools-fabric-1.21.1-0.1.4-dev.jar` |
| `fabric-262` | 26.2 | Fabric | `cd fabric-262 && ./gradlew jar` | `fabric-262/build/libs/buildertools-fabric-26.2-0.1.4.jar` |
| `forge-262` | 26.2 | Forge | `cd forge-262 && ./gradlew jar` | `forge-262/build/libs/buildertools-forge-26.2-0.1.4.jar` |
| `neoforge-262` | 26.2 | NeoForge | `cd neoforge-262 && ./gradlew jar` | `neoforge-262/build/libs/buildertools-neoforge-26.2-0.1.4.jar` |

FullSlabs and Flexible Paintings are bundled in the root Forge/NeoForge and Fabric 1.21.1 jars. The 26.2 modules retain their version-specific source adapters.

The root 1.21.1 metadata uses a broad Minecraft compatibility range (`[1.21.1,1.22)`) for runtime version checks. The compiled jars still target the exact 1.21.1 APIs and must be rebuilt or API-adapted for later 1.21.x releases where Minecraft changes binary/API behavior.
