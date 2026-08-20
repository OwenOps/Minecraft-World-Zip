package com.worldzip.client.mixin;

import com.worldzip.WorldZip;
import com.worldzip.client.WorldZipListFilter;
import com.worldzip.client.WorldZipListRefilter;
import com.worldzip.client.ZippedWorldList;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldSelectionList.class)
public abstract class WorldSelectionListMixin implements WorldZipListRefilter {

    @Shadow
    private @Nullable List<LevelSummary> currentlyDisplayedLevels;

    @Shadow
    private String filter;

    @Shadow
    private void fillLevels(String filter, List<LevelSummary> levels) {}

    @Override
    public void worldzip$reapplyFilter() {
        if (this.currentlyDisplayedLevels != null) {
            this.fillLevels(this.filter, this.currentlyDisplayedLevels);
        }
    }

    @Inject(method = "filterAccepts", at = @At("HEAD"), cancellable = true)
    private void worldzip$kindFilter(String search, LevelSummary level, CallbackInfoReturnable<Boolean> cir) {
        if (!WorldZipListFilter.current().accepts(level)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "loadLevels", at = @At("RETURN"), cancellable = true)
    private void worldzip$appendZippedWorlds(CallbackInfoReturnable<CompletableFuture<List<LevelSummary>>> cir) {
        Path savesDir = Minecraft.getInstance().getLevelSource().getBaseDir();
        CompletableFuture<List<LevelSummary>> zips = CompletableFuture.supplyAsync(
            () -> ZippedWorldList.load(savesDir),
            Util.backgroundExecutor().forName("worldzipZips")
        ).exceptionally(throwable -> {
            WorldZip.LOGGER.error("Could not list zipped worlds", throwable);
            return List.of();
        });
        cir.setReturnValue(cir.getReturnValue().thenCombine(zips, (folders, zipList) -> {
            ZippedWorldList.attachFolderSizes(folders, savesDir);
            return ZippedWorldList.merge(folders, zipList);
        }));
    }
}
