package com.worldzip.client;

import com.worldzip.WorldZip;
import com.worldzip.archive.ByteFormat;
import com.worldzip.archive.WorldArchive;
import com.worldzip.archive.WorldArchiveException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;

/**
 * Select World zip / unzip actions. File work runs off the client thread.
 */
public final class WorldZipSelectWorld {

    private WorldZipSelectWorld() {}

    public static boolean canZip(@Nullable LevelSummary summary) {
        return summary != null && zipBlockedReason(summary) == null;
    }

    public static boolean canUnzip(@Nullable LevelSummary summary) {
        return summary instanceof ZippedLevelSummary zipped && unzipBlockedReason(zipped) == null;
    }

    /**
     * @return why {@code summary} cannot be zipped right now, or {@code null} if it can be. Used both
     *     to gate the Zip button and to explain the block to the player via a tooltip.
     */
    public static @Nullable Component zipBlockedReason(LevelSummary summary) {
        if (summary instanceof ZippedLevelSummary) {
            return Component.translatable("worldzip.button.zip.tooltip.alreadyZipped");
        }
        if (summary instanceof LevelSummary.SymlinkLevelSummary || summary instanceof LevelSummary.CorruptedLevelSummary) {
            return Component.translatable("worldzip.button.zip.tooltip.invalid");
        }
        if (summary.isLocked()) {
            return Component.translatable("worldzip.button.zip.tooltip.locked");
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path worldDir = minecraft.getLevelSource().getLevelPath(summary.getLevelId());
        if (!WorldArchive.isWorldFolder(worldDir)) {
            return Component.translatable("worldzip.button.zip.tooltip.invalid");
        }
        Path zip = worldDir.resolveSibling(worldDir.getFileName().toString() + WorldArchive.ZIP_EXTENSION);
        if (Files.exists(zip)) {
            return Component.translatable("worldzip.button.zip.tooltip.exists");
        }
        return null;
    }

    public static @Nullable Component unzipBlockedReason(ZippedLevelSummary summary) {
        Path dest = WorldArchive.unzipDestination(summary.zipPath());
        if (Files.exists(dest)) {
            return Component.translatable("worldzip.button.unzip.tooltip.exists");
        }
        return null;
    }

    public static void onArchiveButton(Screen selectWorldScreen, WorldSelectionList list) {
        list.getSelectedOpt().ifPresent(entry -> {
            LevelSummary summary = entry.getLevelSummary();
            if (summary instanceof ZippedLevelSummary zipped) {
                unzipWorld(selectWorldScreen, list, zipped);
            } else {
                zipWorld(selectWorldScreen, list, summary);
            }
        });
    }

    public static void zipWorld(Screen selectWorldScreen, WorldSelectionList list, LevelSummary summary) {
        if (!canZip(summary)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path worldDir = minecraft.getLevelSource().getLevelPath(summary.getLevelId());
        long size = folderSizeQuiet(worldDir);
        Component message = size >= WorldArchive.LARGE_WORLD_BYTES
            ? Component.translatable("worldzip.zip.confirm.message.large", summary.getLevelName(), ByteFormat.human(size))
            : Component.translatable("worldzip.zip.confirm.message", summary.getLevelName(), ByteFormat.human(size));
        minecraft.setScreenAndShow(
            new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        doZipWorld(selectWorldScreen, list, summary, worldDir);
                    } else {
                        minecraft.setScreenAndShow(selectWorldScreen);
                    }
                },
                Component.translatable("worldzip.zip.confirm.title"),
                message,
                Component.translatable("worldzip.zip.confirm.yes"),
                CommonComponents.GUI_CANCEL
            )
        );
    }

    private static void doZipWorld(Screen selectWorldScreen, WorldSelectionList list, LevelSummary summary, Path worldDir) {
        Minecraft minecraft = Minecraft.getInstance();
        WorldZipProgressScreen progress = new WorldZipProgressScreen(Component.translatable("worldzip.zipping"));
        minecraft.setScreenAndShow(progress);
        Util.backgroundExecutor().execute(() -> {
            try {
                WorldArchive.ZipResult result = WorldArchive.zipReplace(worldDir, progress::isCancelled, progress.progress());
                minecraft.execute(() -> {
                    list.returnToScreen();
                    showZipSaved(minecraft, result);
                });
            } catch (WorldArchiveException e) {
                if (e.isCancelled()) {
                    minecraft.execute(list::returnToScreen);
                } else {
                    WorldZip.LOGGER.error("Could not zip world {}", summary.getLevelId(), e);
                    minecraft.execute(() -> {
                        showFailure(minecraft, "worldzip.zip.failed", e);
                        minecraft.gui.setScreen(selectWorldScreen);
                    });
                }
            } catch (Exception e) {
                WorldZip.LOGGER.error("Could not zip world {}", summary.getLevelId(), e);
                minecraft.execute(() -> {
                    showFailure(minecraft, "worldzip.zip.failed", e);
                    minecraft.gui.setScreen(selectWorldScreen);
                });
            }
        });
    }

    public static void unzipWorld(Screen selectWorldScreen, WorldSelectionList list, ZippedLevelSummary summary) {
        if (!canUnzip(summary)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreenAndShow(
            new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        doUnzipWorld(selectWorldScreen, list, summary, false);
                    } else {
                        minecraft.setScreenAndShow(selectWorldScreen);
                    }
                },
                Component.translatable("worldzip.unzip.confirm.title"),
                Component.translatable("worldzip.unzip.confirm.message", summary.getLevelName(), ByteFormat.human(summary.zipBytes())),
                Component.translatable("worldzip.unzip.confirm.yes"),
                CommonComponents.GUI_CANCEL
            )
        );
    }

    public static void playZipped(WorldSelectionList list, ZippedLevelSummary summary) {
        doUnzipWorld(null, list, summary, true);
    }

    private static void doUnzipWorld(@Nullable Screen selectWorldScreen, WorldSelectionList list, ZippedLevelSummary summary, boolean thenPlay) {
        Minecraft minecraft = Minecraft.getInstance();
        WorldZipProgressScreen progress = new WorldZipProgressScreen(Component.translatable("worldzip.unzipping"));
        minecraft.setScreenAndShow(progress);
        Util.backgroundExecutor().execute(() -> {
            try {
                Path dest = WorldArchive.unzipReplace(summary.zipPath(), progress::isCancelled, progress.progress());
                String levelId = dest.getFileName().toString();
                minecraft.execute(() -> {
                    if (thenPlay) {
                        minecraft.createWorldOpenFlows().openWorld(levelId, list::returnToScreen);
                    } else {
                        list.returnToScreen();
                    }
                });
            } catch (WorldArchiveException e) {
                if (e.isCancelled()) {
                    minecraft.execute(list::returnToScreen);
                } else {
                    WorldZip.LOGGER.error("Could not unzip world {}", summary.zipPath().getFileName(), e);
                    minecraft.execute(() -> {
                        showFailure(minecraft, "worldzip.unzip.failed", e);
                        if (selectWorldScreen != null) {
                            minecraft.gui.setScreen(selectWorldScreen);
                        } else {
                            list.returnToScreen();
                        }
                    });
                }
            } catch (Exception e) {
                WorldZip.LOGGER.error("Could not unzip world {}", summary.zipPath().getFileName(), e);
                minecraft.execute(() -> {
                    showFailure(minecraft, "worldzip.unzip.failed", e);
                    list.returnToScreen();
                });
            }
        });
    }

    public static void deleteZip(ZippedLevelSummary summary) {
        try {
            Files.deleteIfExists(summary.zipPath());
        } catch (Exception e) {
            Minecraft minecraft = Minecraft.getInstance();
            WorldZip.LOGGER.error("Could not delete zipped world {}", summary.zipPath().getFileName(), e);
            SystemToast.onWorldDeleteFailure(minecraft, summary.getLevelId());
        }
    }

    public static void zipAll(Screen selectWorldScreen, WorldSelectionList list) {
        Minecraft minecraft = Minecraft.getInstance();
        List<Path> targets = zippableWorldDirs(minecraft.getLevelSource().getBaseDir());
        if (targets.isEmpty()) {
            SystemToast.add(
                minecraft.gui.toastManager(),
                SystemToast.SystemToastId.WORLD_BACKUP,
                Component.translatable("worldzip.zipall.none.title"),
                Component.translatable("worldzip.zipall.none.detail")
            );
            return;
        }
        minecraft.setScreenAndShow(
            new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        doZipAll(list, targets);
                    } else {
                        minecraft.setScreenAndShow(selectWorldScreen);
                    }
                },
                Component.translatable("worldzip.zipall.confirm.title"),
                Component.translatable("worldzip.zipall.confirm.message", targets.size()),
                Component.translatable("worldzip.zipall.confirm.yes"),
                CommonComponents.GUI_CANCEL
            )
        );
    }

    public static void unzipAll(Screen selectWorldScreen, WorldSelectionList list) {
        Minecraft minecraft = Minecraft.getInstance();
        List<Path> targets = unzippableZips(minecraft.getLevelSource().getBaseDir());
        if (targets.isEmpty()) {
            SystemToast.add(
                minecraft.gui.toastManager(),
                SystemToast.SystemToastId.WORLD_BACKUP,
                Component.translatable("worldzip.unzipall.none.title"),
                Component.translatable("worldzip.unzipall.none.detail")
            );
            return;
        }
        minecraft.setScreenAndShow(
            new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        doUnzipAll(list, targets);
                    } else {
                        minecraft.setScreenAndShow(selectWorldScreen);
                    }
                },
                Component.translatable("worldzip.unzipall.confirm.title"),
                Component.translatable("worldzip.unzipall.confirm.message", targets.size()),
                Component.translatable("worldzip.unzipall.confirm.yes"),
                CommonComponents.GUI_CANCEL
            )
        );
    }

    private static void doZipAll(WorldSelectionList list, List<Path> targets) {
        Minecraft minecraft = Minecraft.getInstance();
        WorldZipProgressScreen progress = new WorldZipProgressScreen(batchProgress("worldzip.zipall.progress", 1, targets.size(), targets.get(0)));
        minecraft.setScreenAndShow(progress);
        Util.backgroundExecutor().execute(() -> {
            int zipped = 0;
            int failed = 0;
            String firstFailed = null;
            long saved = 0;
            for (int i = 0; i < targets.size(); i++) {
                if (progress.isCancelled()) {
                    break;
                }
                Path worldDir = targets.get(i);
                int oneBasedIndex = i + 1;
                minecraft.execute(() -> progress.setMessage(batchProgress("worldzip.zipall.progress", oneBasedIndex, targets.size(), worldDir)));
                try {
                    WorldArchive.ZipResult result = WorldArchive.zipReplace(worldDir, progress::isCancelled, progress.progress());
                    zipped++;
                    saved += result.savedBytes();
                } catch (WorldArchiveException e) {
                    if (e.isCancelled()) {
                        break;
                    }
                    WorldZip.LOGGER.warn("Zip All: failed {}: {}", worldDir.getFileName(), e.toString());
                    failed++;
                    if (firstFailed == null) {
                        firstFailed = worldDir.getFileName().toString();
                    }
                } catch (Exception e) {
                    WorldZip.LOGGER.warn("Zip All: failed {}: {}", worldDir.getFileName(), e.toString());
                    failed++;
                    if (firstFailed == null) {
                        firstFailed = worldDir.getFileName().toString();
                    }
                }
            }
            int finalZipped = zipped;
            int finalFailed = failed;
            String finalFirstFailed = firstFailed;
            long finalSaved = saved;
            minecraft.execute(() -> {
                list.returnToScreen();
                showBatchSummary(minecraft, "worldzip.zipall.done.title", finalZipped, finalFailed, finalFirstFailed, finalSaved);
            });
        });
    }

    private static void doUnzipAll(WorldSelectionList list, List<Path> targets) {
        Minecraft minecraft = Minecraft.getInstance();
        WorldZipProgressScreen progress = new WorldZipProgressScreen(batchProgress("worldzip.unzipall.progress", 1, targets.size(), targets.get(0)));
        minecraft.setScreenAndShow(progress);
        Util.backgroundExecutor().execute(() -> {
            int unzipped = 0;
            int failed = 0;
            String firstFailed = null;
            for (int i = 0; i < targets.size(); i++) {
                if (progress.isCancelled()) {
                    break;
                }
                Path zip = targets.get(i);
                int oneBasedIndex = i + 1;
                minecraft.execute(() -> progress.setMessage(batchProgress("worldzip.unzipall.progress", oneBasedIndex, targets.size(), zip)));
                try {
                    WorldArchive.unzipReplace(zip, progress::isCancelled, progress.progress());
                    unzipped++;
                } catch (WorldArchiveException e) {
                    if (e.isCancelled()) {
                        break;
                    }
                    WorldZip.LOGGER.warn("Unzip All: failed {}: {}", zip.getFileName(), e.toString());
                    failed++;
                    if (firstFailed == null) {
                        firstFailed = zip.getFileName().toString();
                    }
                } catch (Exception e) {
                    WorldZip.LOGGER.warn("Unzip All: failed {}: {}", zip.getFileName(), e.toString());
                    failed++;
                    if (firstFailed == null) {
                        firstFailed = zip.getFileName().toString();
                    }
                }
            }
            int finalUnzipped = unzipped;
            int finalFailed = failed;
            String finalFirstFailed = firstFailed;
            minecraft.execute(() -> {
                list.returnToScreen();
                showBatchSummary(minecraft, "worldzip.unzipall.done.title", finalUnzipped, finalFailed, finalFirstFailed, -1L);
            });
        });
    }

    static List<Path> zippableWorldDirs(Path savesDir) {
        List<Path> result = new ArrayList<>();
        if (savesDir == null || !Files.isDirectory(savesDir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.startsWith(".") || WorldArchive.isOrphanTemp(path) || !WorldArchive.isWorldFolder(path)) {
                    continue;
                }
                Path zip = path.resolveSibling(name + WorldArchive.ZIP_EXTENSION);
                if (Files.exists(zip) || WorldArchive.isSessionLockHeld(path)) {
                    continue;
                }
                result.add(path);
            }
        } catch (IOException e) {
            WorldZip.LOGGER.error("Could not list worlds to zip in {}", savesDir, e);
        }
        result.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return result;
    }

    static List<Path> unzippableZips(Path savesDir) {
        List<Path> result = new ArrayList<>();
        if (savesDir == null || !Files.isDirectory(savesDir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path path : stream) {
                if (!WorldArchive.isZipFile(path)) {
                    continue;
                }
                try {
                    WorldArchive.peek(path);
                } catch (Exception e) {
                    continue;
                }
                if (Files.exists(WorldArchive.unzipDestination(path))) {
                    continue;
                }
                result.add(path);
            }
        } catch (IOException e) {
            WorldZip.LOGGER.error("Could not list zips to unzip in {}", savesDir, e);
        }
        result.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return result;
    }

    private static Component batchProgress(String key, int oneBasedIndex, int total, Path path) {
        return Component.translatable(key, oneBasedIndex, total, WorldArchive.stripZipExtension(path.getFileName().toString()));
    }

    private static long folderSizeQuiet(Path worldDir) {
        try {
            return WorldArchive.sourceBytes(worldDir);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void showZipSaved(Minecraft minecraft, WorldArchive.ZipResult result) {
        Component detail = result.savedBytes() > 0
            ? Component.translatable("worldzip.zip.done.saved", ByteFormat.human(result.savedBytes()), ByteFormat.human(result.zipBytes()))
            : Component.translatable("worldzip.zip.done.detail", ByteFormat.human(result.zipBytes()));
        SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.WORLD_BACKUP, Component.translatable("worldzip.zip.done.title"), detail);
    }

    private static void showBatchSummary(Minecraft minecraft, String titleKey, int ok, int failed, @Nullable String firstFailed, long savedBytes) {
        Component detail;
        if (failed > 0 && firstFailed != null) {
            detail = failed == 1
                ? Component.translatable("worldzip.batch.done.oneFailed", ok, firstFailed)
                : Component.translatable("worldzip.batch.done.failed", ok, failed);
        } else if (savedBytes > 0) {
            detail = Component.translatable("worldzip.batch.done.saved", ok, ByteFormat.human(savedBytes));
        } else {
            detail = Component.translatable("worldzip.batch.done.ok", ok);
        }
        SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.WORLD_BACKUP, Component.translatable(titleKey), detail);
    }

    private static void showFailure(Minecraft minecraft, String titleKey, Exception e) {
        String detail = e instanceof WorldArchiveException ? e.getMessage() : e.toString();
        SystemToast.add(
            minecraft.gui.toastManager(),
            SystemToast.SystemToastId.WORLD_ACCESS_FAILURE,
            Component.translatable(titleKey),
            Component.literal(detail)
        );
    }
}
