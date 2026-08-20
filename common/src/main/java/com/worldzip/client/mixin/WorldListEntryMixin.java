package com.worldzip.client.mixin;

import com.worldzip.client.WorldZipSelectWorld;
import com.worldzip.client.ZippedLevelSummary;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {

    @Shadow
    @Final
    private WorldSelectionList list;

    @Shadow
    @Final
    private LevelSummary summary;

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void worldzip$unzipThenJoin(CallbackInfo ci) {
        if (this.summary instanceof ZippedLevelSummary zipped) {
            WorldZipSelectWorld.playZipped(this.list, zipped);
            ci.cancel();
        }
    }

    @Inject(method = "doDeleteWorld", at = @At("HEAD"), cancellable = true)
    private void worldzip$deleteZip(CallbackInfo ci) {
        if (this.summary instanceof ZippedLevelSummary zipped) {
            WorldZipSelectWorld.deleteZip(zipped);
            ci.cancel();
        }
    }

    @Inject(method = "editWorld", at = @At("HEAD"), cancellable = true)
    private void worldzip$noEditZip(CallbackInfo ci) {
        if (this.summary instanceof ZippedLevelSummary) {
            ci.cancel();
        }
    }

    @Inject(method = "recreateWorld", at = @At("HEAD"), cancellable = true)
    private void worldzip$noRecreateZip(CallbackInfo ci) {
        if (this.summary instanceof ZippedLevelSummary) {
            ci.cancel();
        }
    }
}
