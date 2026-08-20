package com.worldzip.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.worldzip.client.WorldZipSelectWorld;
import com.worldzip.client.ZippedLevelSummary;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin {

    @Shadow
    private @Nullable WorldSelectionList list;

    @Shadow
    private @Nullable Button editButton;

    @Shadow
    private @Nullable Button recreateButton;

    @Unique
    private @Nullable Button worldzip$zipButton;

    @Unique
    private @Nullable Button worldzip$zipAllButton;

    @Unique
    private @Nullable Button worldzip$unzipAllButton;

    /**
     * "Zip All" sits next to the search box in the header: it acts on every world in {@code saves/},
     * not the selected one, so it does not belong in the per-selection footer row.
     */
    @WrapOperation(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/LinearLayout;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
            ordinal = 3
        )
    )
    private LayoutElement worldzip$insertZipAllButton(LinearLayout subHeader, LayoutElement searchBox, Operation<LayoutElement> original) {
        LayoutElement result = original.call(subHeader, searchBox);
        SelectWorldScreen screen = (SelectWorldScreen) (Object) this;
        this.worldzip$zipAllButton = subHeader.addChild(
            Button.builder(Component.translatable("worldzip.button.zipAll"), button -> {
                if (this.list != null) {
                    WorldZipSelectWorld.zipAll(screen, this.list);
                }
            })
                .width(72)
                .build()
        );
        this.worldzip$unzipAllButton = subHeader.addChild(
            Button.builder(Component.translatable("worldzip.button.unzipAll"), button -> {
                if (this.list != null) {
                    WorldZipSelectWorld.unzipAll(screen, this.list);
                }
            })
                .width(80)
                .build()
        );
        return result;
    }

    /**
     * 5 footer columns instead of vanilla's 4, so row 1 (Edit, Delete, Zip, Recreate, Back)
     * has 5 even-width buttons instead of spilling "Back" onto its own row.
     */
    @ModifyArg(
        method = "createFooterButtons",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout;createRowHelper(I)Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;"
        )
    )
    private int worldzip$fiveFooterColumns(int columns) {
        return 5;
    }

    /**
     * Play widens from span 2 to span 3 so row 0 (Play, Create) also fills all 5 columns,
     * matching row 1 instead of leaving a gap.
     */
    @ModifyArg(
        method = "createFooterButtons",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;",
            ordinal = 0
        )
    )
    private int worldzip$widenPlayButton(int span) {
        return 3;
    }

    /**
     * Row 1 buttons (Edit, Delete, Recreate, Back) are added one at a time via
     * {@code RowHelper.addChild(widget)}. Ordinal 2 of that call is Recreate; inserting Zip
     * right before it puts Zip between Delete and Recreate.
     */
    @WrapOperation(
        method = "createFooterButtons",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
            ordinal = 2
        )
    )
    private LayoutElement worldzip$insertZipButton(
        GridLayout.RowHelper helper,
        LayoutElement recreateButton,
        Operation<LayoutElement> original
    ) {
        SelectWorldScreen screen = (SelectWorldScreen) (Object) this;
        this.worldzip$zipButton = helper.addChild(
            Button.builder(Component.translatable("worldzip.button.zip"), button -> {
                if (this.list != null) {
                    WorldZipSelectWorld.onArchiveButton(screen, this.list);
                }
            })
                .width(71)
                .build()
        );
        return original.call(helper, recreateButton);
    }

    @Inject(method = "updateButtonStatus", at = @At("TAIL"))
    private void worldzip$updateZipButton(@Nullable LevelSummary summary, CallbackInfo ci) {
        if (this.worldzip$zipButton != null) {
            if (summary instanceof ZippedLevelSummary zipped) {
                this.worldzip$zipButton.setMessage(Component.translatable("worldzip.button.unzip"));
                this.worldzip$zipButton.active = WorldZipSelectWorld.canUnzip(zipped);
                Component reason = WorldZipSelectWorld.unzipBlockedReason(zipped);
                this.worldzip$zipButton.setTooltip(
                    reason == null
                        ? Tooltip.create(Component.translatable("worldzip.button.unzip.tooltip"))
                        : Tooltip.create(reason)
                );
            } else {
                this.worldzip$zipButton.setMessage(Component.translatable("worldzip.button.zip"));
                this.worldzip$zipButton.active = WorldZipSelectWorld.canZip(summary);
                Component reason = summary == null ? null : WorldZipSelectWorld.zipBlockedReason(summary);
                this.worldzip$zipButton.setTooltip(reason == null ? null : Tooltip.create(reason));
            }
        }
        if (summary instanceof ZippedLevelSummary) {
            Tooltip zippedTooltip = Tooltip.create(Component.translatable("worldzip.tooltip.zippedDisabled"));
            if (this.editButton != null) {
                this.editButton.setTooltip(zippedTooltip);
            }
            if (this.recreateButton != null) {
                this.recreateButton.setTooltip(zippedTooltip);
            }
        }
    }
}
