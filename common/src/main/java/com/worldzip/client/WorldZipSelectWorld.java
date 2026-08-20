package com.worldzip.client;

import com.worldzip.WorldZip;
import com.worldzip.archive.WorldArchive;
import com.worldzip.archive.WorldArchiveException;
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
        if (!Files.isDirectory(worldDir) || !Files.isRegularFile(worldDir.resolve(WorldArchive.LEVEL_DAT))) {
            return Component.translatable("worldzip.button.zip.tooltip.invalid");
        }
        Path zip = worldDir.resolveSibling(worldDir.getFileName().toString() + WorldArchive.ZIP_EXTENSION);
        if (Files.exists(zip)) {
            return Component.translatable("worldzip.button.zip.tooltip.exists");
        }
        return null;
    }

    public static void zipSelected(Screen selectWorldScreen, WorldSelectionList list) {
        list.getSelectedOpt().ifPresent(entry -> zipWorld(selectWorldScreen, list, entry.getLevelSummary()));
    }

    public static void zipWorld(Screen selectWorldScreen, WorldSelectionList list, LevelSummary summary) {
        if (!canZip(summary)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreenAndShow(
            new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        doZipWorld(selectWorldScreen, list, summary);
                    } else {
                        minecraft.setScreenAndShow(selectWorldScreen);
                    }
                },
                Component.translatable("worldzip.zip.confirm.title"),
                Component.translatable("worldzip.zip.confirm.message", summary.getLevelName()),
                Component.translatable("worldzip.zip.confirm.yes"),
                CommonComponents.GUI_CANCEL
            )
        );
    }

    private static void doZipWorld(Screen selectWorldScreen, WorldSelectionList list, LevelSummary summary) {
        Minecraft minecraft = Minecraft.getInstance();
        Path worldDir = minecraft.getLevelSource().getLevelPath(summary.getLevelId());
        WorldZipProgressScreen progress = new WorldZipProgressScreen(Component.translatable("worldzip.zipping"));
        minecraft.setScreenAndShow(progress);
        Util.backgroundExecutor().execute(() -> {
            try {
                WorldArchive.zipReplace(worldDir, progress::isCancelled);
                minecraft.execute(list::returnToScreen);
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

    public static void playZipped(WorldSelectionList list, ZippedLevelSummary summary) {
        Minecraft minecraft = Minecraft.getInstance();
        WorldZipProgressScreen progress = new WorldZipProgressScreen(Component.translatable("worldzip.unzipping"));
        minecraft.setScreenAndShow(progress);
        Util.backgroundExecutor().execute(() -> {
            try {
                Path dest = WorldArchive.unzipReplace(summary.zipPath(), progress::isCancelled);
                String levelId = dest.getFileName().toString();
                minecraft.execute(() -> minecraft.createWorldOpenFlows().openWorld(levelId, list::returnToScreen));
            } catch (WorldArchiveException e) {
                if (e.isCancelled()) {
                    minecraft.execute(list::returnToScreen);
                } else {
                    WorldZip.LOGGER.error("Could not unzip world {}", summary.zipPath().getFileName(), e);
                    minecraft.execute(() -> {
                        showFailure(minecraft, "worldzip.unzip.failed", e);
                        list.returnToScreen();
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

    /**
     * @return every world currently in {@code list} that {@link #canZip} allows zipping. Zipped,
     *     locked, and otherwise invalid entries are left out rather than reported as failures.
     */
    public static List<LevelSummary> zippableWorlds(WorldSelectionList list) {
        List<LevelSummary> result = new ArrayList<>();
        for (WorldSelectionList.Entry entry : list.children()) {
            LevelSummary summary = entry.getLevelSummary();
            if (canZip(summary)) {
                result.add(summary);
            }
        }
        return result;
    }

    public static void zipAll(Screen selectWorldScreen, WorldSelectionList list) {
        List<LevelSummary> targets = zippableWorlds(list);
        Minecraft minecraft = Minecraft.getInstance();
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

    private static void doZipAll(WorldSelectionList list, List<LevelSummary> targets) {
        Minecraft minecraft = Minecraft.getInstance();
        WorldZipProgressScreen progress = new WorldZipProgressScreen(zipAllProgressMessage(1, targets.size(), targets.get(0)));
        minecraft.setScreenAndShow(progress);
        Util.backgroundExecutor().execute(() -> {
            int zipped = 0;
            int skipped = 0;
            for (int i = 0; i < targets.size(); i++) {
                if (progress.isCancelled()) {
                    skipped += targets.size() - i;
                    break;
                }
                LevelSummary summary = targets.get(i);
                int oneBasedIndex = i + 1;
                minecraft.execute(() -> progress.setMessage(zipAllProgressMessage(oneBasedIndex, targets.size(), summary)));
                Path worldDir = minecraft.getLevelSource().getLevelPath(summary.getLevelId());
                try {
                    WorldArchive.zipReplace(worldDir, progress::isCancelled);
                    zipped++;
                } catch (WorldArchiveException e) {
                    if (e.isCancelled()) {
                        skipped += targets.size() - i;
                        break;
                    }
                    WorldZip.LOGGER.warn("Zip All: skipping {}: {}", summary.getLevelId(), e.toString());
                    skipped++;
                } catch (Exception e) {
                    WorldZip.LOGGER.warn("Zip All: skipping {}: {}", summary.getLevelId(), e.toString());
                    skipped++;
                }
            }
            int finalZipped = zipped;
            int finalSkipped = skipped;
            minecraft.execute(() -> {
                list.returnToScreen();
                showZipAllSummary(minecraft, finalZipped, finalSkipped);
            });
        });
    }

    private static Component zipAllProgressMessage(int oneBasedIndex, int total, LevelSummary summary) {
        return Component.translatable("worldzip.zipall.progress", oneBasedIndex, total, summary.getLevelName());
    }

    private static void showZipAllSummary(Minecraft minecraft, int zipped, int skipped) {
        Component detail = skipped > 0
            ? Component.translatable("worldzip.zipall.done.detailWithSkipped", zipped, skipped)
            : Component.translatable("worldzip.zipall.done.detail", zipped);
        SystemToast.add(minecraft.gui.toastManager(), SystemToast.SystemToastId.WORLD_BACKUP, Component.translatable("worldzip.zipall.done.title"), detail);
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
