# World Zip

Zip Minecraft **26.2** worlds to save disk space. Play a zipped world from the Select World screen: the mod unzips it in place, then vanilla loads the folder.

## Requirements

- Minecraft 26.2
- Java 25
- **Fabric** (with [Fabric API](https://modrinth.com/mod/fabric-api)) **or** **NeoForge**

Use the JAR that matches your loader. Do not install both. Client and server both need the mod if you play on a dedicated server.

## How to use

On the Select World screen:

1. Select a world folder and click **Zip** (between Delete and Recreate), then confirm. The folder is replaced with `name.zip`.
2. Or click **Zip All** (next to the search box) to zip every eligible world in one go, after a single confirmation. Already-zipped, open, or invalid worlds are skipped.
3. Zipped worlds show up in the list (marked **Zipped**). **Play** unzips them in place, then vanilla loads the folder.
4. Only archives that contain a real `level.dat` (and pass path / size checks) are treated as worlds. Edit and Recreate stay off until the world is a folder again.
5. Zipping/unzipping shows a progress screen with a **Cancel** button; cancelling leaves the original world untouched.

## Build

```bat
.\gradlew.bat assemble
```

JARs:

- `fabric/build/libs/worldzip-fabric-26.2-1.0.0.jar`
- `neoforge/build/libs/worldzip-neoforge-26.2-1.0.0.jar`

Copy one of them with:

```bat
.\gradlew.bat :fabric:copyToMinecraftMods
.\gradlew.bat :neoforge:copyToMinecraftMods
```

Do not run `assemble` while a NeoForge `runClient` / `runServer` is still open (Windows file lock).

## License

[MIT](LICENSE.txt)
