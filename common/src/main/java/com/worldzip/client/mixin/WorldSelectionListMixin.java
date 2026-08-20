package com.worldzip.client.mixin;

import com.worldzip.WorldZip;
import com.worldzip.client.ZippedWorldList;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldSelectionList.class)
public abstract class WorldSelectionListMixin {

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
        cir.setReturnValue(cir.getReturnValue().thenCombine(zips, ZippedWorldList::merge));
    }
}
