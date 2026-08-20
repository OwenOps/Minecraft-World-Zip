package com.worldzip.client;

import com.mojang.serialization.Dynamic;
import com.worldzip.WorldZip;
import com.worldzip.archive.WorldArchive;
import com.worldzip.archive.WorldArchiveException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.LevelVersion;

/**
 * Lists {@code .zip} worlds next to vanilla folders in {@code saves/}.
 */
public final class ZippedWorldList {

    private static final int ANVIL_VERSION = 19133;

    private ZippedWorldList() {}

    public static List<LevelSummary> load(Path savesDir) {
        List<LevelSummary> zips = new ArrayList<>();
        if (savesDir == null || !Files.isDirectory(savesDir)) {
            return zips;
        }
        pruneIconCache(savesDir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path path : stream) {
                if (WorldArchive.isOrphanTemp(path)) {
                    WorldZip.LOGGER.info("Removing orphaned temp file from an interrupted zip/unzip: {}", path.getFileName());
                    WorldArchive.deleteOrphanTemp(path);
                    continue;
                }
                if (!WorldArchive.isZipFile(path)) {
                    continue;
                }
                try {
                    zips.add(readSummary(savesDir, path));
                } catch (Exception e) {
                    WorldZip.LOGGER.debug("Skipping {}: {}", path.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            WorldZip.LOGGER.error("Could not list zipped worlds in {}", savesDir, e);
        }
        return zips;
    }

    /**
     * Walk each folder world once (on the list-load thread) so {@link LevelSummary#getInfo()} can
     * show disk size without hitting the filesystem while the list renders.
     */
    public static void attachFolderSizes(List<LevelSummary> folders, Path savesDir) {
        if (savesDir == null || folders == null) {
            return;
        }
        for (LevelSummary summary : folders) {
            if (summary instanceof ZippedLevelSummary || !(summary instanceof WorldZipFolderSize holder)) {
                continue;
            }
            Path dir = savesDir.resolve(summary.getLevelId());
            if (!WorldArchive.isWorldFolder(dir)) {
                continue;
            }
            try {
                holder.worldzip$setFolderBytes(WorldArchive.sourceBytes(dir));
            } catch (IOException e) {
                WorldZip.LOGGER.debug("Could not measure {}: {}", dir.getFileName(), e.toString());
            }
        }
    }

    public static List<LevelSummary> merge(List<LevelSummary> folders, List<LevelSummary> zips) {
        List<LevelSummary> merged = new ArrayList<>(folders.size() + zips.size());
        merged.addAll(folders);
        merged.addAll(zips);
        merged.sort(null);
        return List.copyOf(merged);
    }

    private static LevelSummary readSummary(Path savesDir, Path zip) throws WorldArchiveException, IOException {
        WorldArchive.WorldPeekResult peekResult = WorldArchive.peekWithLevelDat(zip);
        WorldArchive.WorldPeek peek = peekResult.peek();
        byte[] levelDat = peekResult.levelDat();
        Path icon = cacheIcon(savesDir, zip, peekResult.iconPng(), peek.folderName());
        long zipBytes = Files.size(zip);
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(levelDat), NbtAccounter.uncompressedQuota());
            CompoundTag tag = root.getCompoundOrEmpty("Data");
            int dataVersion = NbtUtils.getDataVersion(tag);
            Dynamic<?> updated = DataFixTypes.LEVEL_SUMMARY.updateToCurrentVersion(
                DataFixers.getDataFixer(),
                new Dynamic<>(NbtOps.INSTANCE, tag),
                dataVersion
            );
            LevelVersion levelVersion = LevelVersion.parse(updated);
            boolean requiresManualConversion = levelVersion.levelDataVersion() != ANVIL_VERSION;
            boolean requiresFileFixing = DataFixers.getFileFixer().requiresFileFixing(dataVersion);
            WorldDataConfiguration dataConfiguration = LevelStorageSource.readDataConfig(updated);
            LevelSettings settings = LevelSettings.parse(updated, dataConfiguration);
            boolean experimental = isExperimental(updated);
            return new ZippedLevelSummary(
                zip,
                settings,
                levelVersion,
                requiresManualConversion,
                requiresFileFixing,
                experimental,
                icon,
                zipBytes
            );
        } catch (Exception e) {
            WorldZip.LOGGER.debug("Could not parse level.dat in {}, using folder name", zip.getFileName(), e);
            return fallbackSummary(zip, peek.folderName(), icon, zipBytes);
        }
    }

    private static ZippedLevelSummary fallbackSummary(Path zip, String folderName, Path icon, long zipBytes) {
        Dynamic<?> empty = new Dynamic<>(NbtOps.INSTANCE, new CompoundTag());
        LevelSettings settings = new LevelSettings(
            folderName,
            GameType.SURVIVAL,
            LevelSettings.DifficultySettings.DEFAULT,
            false,
            WorldDataConfiguration.DEFAULT
        );
        return new ZippedLevelSummary(zip, settings, LevelVersion.parse(empty), false, false, false, icon, zipBytes);
    }

    private static Path cacheIcon(Path savesDir, Path zip, byte[] iconPng, String folderName) {
        Path missing = zip.resolveSibling(folderName).resolve(WorldArchive.ICON_PNG);
        if (iconPng == null || iconPng.length == 0) {
            return missing;
        }
        try {
            Path cacheDir = savesDir.resolve(WorldArchive.ICON_CACHE_DIR);
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve(zip.getFileName().toString() + ".png");
            if (Files.isRegularFile(cached)
                && Files.getLastModifiedTime(cached).toMillis() >= Files.getLastModifiedTime(zip).toMillis()
                && Files.size(cached) == iconPng.length) {
                return cached;
            }
            Files.write(cached, iconPng);
            return cached;
        } catch (IOException e) {
            WorldZip.LOGGER.debug("Could not cache icon for {}", zip.getFileName(), e);
            return missing;
        }
    }

    private static void pruneIconCache(Path savesDir) {
        Path cacheDir = savesDir.resolve(WorldArchive.ICON_CACHE_DIR);
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir)) {
            for (Path cached : stream) {
                String name = cached.getFileName().toString();
                if (!name.endsWith(".png")) {
                    continue;
                }
                Path zip = savesDir.resolve(name.substring(0, name.length() - ".png".length()));
                if (!Files.exists(zip)) {
                    Files.deleteIfExists(cached);
                }
            }
        } catch (IOException e) {
            WorldZip.LOGGER.debug("Could not prune icon cache in {}", cacheDir, e);
        }
    }

    private static boolean isExperimental(Dynamic<?> dataTag) {
        try {
            Set<Identifier> enabledFlags = dataTag.get("enabled_features")
                .asStream()
                .flatMap(entry -> entry.asString().result().map(Identifier::tryParse).stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            return FeatureFlags.isExperimental(FeatureFlags.REGISTRY.fromNames(enabledFlags, unknownId -> {}));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
