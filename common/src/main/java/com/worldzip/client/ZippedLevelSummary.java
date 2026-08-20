package com.worldzip.client;

import com.worldzip.archive.ByteFormat;
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
    private final long zipBytes;

    public ZippedLevelSummary(
        Path zipPath,
        LevelSettings settings,
        LevelVersion levelVersion,
        boolean requiresManualConversion,
        boolean requiresFileFixing,
        boolean experimental,
        Path icon,
        long zipBytes
    ) {
        super(settings, levelVersion, zipPath.getFileName().toString(), requiresManualConversion, requiresFileFixing, false, experimental, icon);
        this.zipPath = zipPath;
        this.zipBytes = zipBytes;
    }

    public Path zipPath() {
        return this.zipPath;
    }

    public long zipBytes() {
        return this.zipBytes;
    }

    @Override
    public Component getInfo() {
        return Component.empty()
            .append(Component.translatable("worldzip.list.zipped").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" · " + ByteFormat.human(this.zipBytes)))
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
