package com.worldzip.client;

import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.LevelVersion;

/**
 * A {@code .zip} in {@code saves/} that passed {@code WorldArchive.peek}. Play unzips it first.
 */
public final class ZippedLevelSummary extends LevelSummary {

    private final Path zipPath;

    public ZippedLevelSummary(
        Path zipPath,
        LevelSettings settings,
        LevelVersion levelVersion,
        boolean requiresManualConversion,
        boolean requiresFileFixing,
        boolean experimental,
        Path icon
    ) {
        super(settings, levelVersion, zipPath.getFileName().toString(), requiresManualConversion, requiresFileFixing, false, experimental, icon);
        this.zipPath = zipPath;
    }

    public Path zipPath() {
        return this.zipPath;
    }

    @Override
    public Component getInfo() {
        return Component.empty()
            .append(Component.translatable("worldzip.list.zipped").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" — "))
            .append(super.getInfo());
    }

    @Override
    public boolean isDisabled() {
        return false;
    }

    @Override
    public boolean primaryActionActive() {
        return true;
    }

    @Override
    public boolean canEdit() {
        return false;
    }

    @Override
    public boolean canRecreate() {
        return false;
    }

    @Override
    public boolean canUpload() {
        return false;
    }
}
