# World Zip

Zip Minecraft **26.2** worlds to save disk space. Play a zipped world from the Select World screen: the mod unzips it in place, then vanilla loads the folder.

## Requirements

- Minecraft 26.2
- Java 25
- **Fabric** (with [Fabric API](https://modrinth.com/mod/fabric-api)) **or** **NeoForge**

Use the JAR that matches your loader. Do not install both. This is a **client** mod (Select World).

## How to use

On the Select World screen:

1. Select a world folder and click **Zip** (between Delete and Recreate), then confirm. The folder is replaced with `name.zip`.
2. Select a zipped world and click **Unzip** (same button) to extract without playing, or **Play** to unzip and load.
3. **Zip All** / **Unzip All** (next to the search box) act on every eligible world in `saves/`, after one confirmation. Open worlds and name collisions are skipped.
4. Use the **All / Folders / Zipped** cycle button to hide zipped worlds from the list (or show only zips). Files stay in `saves/`; click again to bring them back.
5. Zipped worlds show up in the list (marked **Zipped**, with size and icon) unless Folders is selected. Folder worlds show disk size too. Edit and Recreate stay off until the world is a folder again.
6. Only archives that contain a real `level.dat` (and pass path / size checks) are treated as worlds.
7. Zipping/unzipping shows a progress bar with **Cancel**; cancelling leaves the original untouched.

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
