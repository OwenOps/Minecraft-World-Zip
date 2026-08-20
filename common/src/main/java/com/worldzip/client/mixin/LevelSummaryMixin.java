package com.worldzip.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.worldzip.archive.ByteFormat;
import com.worldzip.client.WorldZipFolderSize;
import com.worldzip.client.ZippedLevelSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelSummary.class)
public abstract class LevelSummaryMixin implements WorldZipFolderSize {

    @Unique
    private long worldzip$folderBytes = -1L;

    @Override
    public void worldzip$setFolderBytes(long bytes) {
        this.worldzip$folderBytes = bytes;
    }

    @ModifyReturnValue(method = "getInfo", at = @At("RETURN"))
    private Component worldzip$appendFolderSize(Component original) {
        if ((Object) this instanceof ZippedLevelSummary || this.worldzip$folderBytes < 0L) {
            return original;
        }
        return Component.empty()
            .append(Component.literal(ByteFormat.human(this.worldzip$folderBytes)).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" — "))
            .append(original);
    }
}
