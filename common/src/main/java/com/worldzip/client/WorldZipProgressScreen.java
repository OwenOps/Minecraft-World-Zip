package com.worldzip.client;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Shown while a zip/unzip runs in the background. Like vanilla's {@code GenericMessageScreen}, but
 * with a Cancel button — vanilla's version has no way out at all.
 */
public final class WorldZipProgressScreen extends Screen {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private @Nullable FocusableTextWidget textWidget;
    private @Nullable Button cancelButton;
    private Component message;

    public WorldZipProgressScreen(Component message) {
        super(message);
        this.message = message;
    }

    @Override
    protected void init() {
        this.textWidget = this.addRenderableWidget(FocusableTextWidget.builder(this.message, this.font, 12).textWidth(this.font.width(this.message)).build());
        this.cancelButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.cancelled.set(true)).build());
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (this.textWidget != null) {
            this.textWidget.setPosition(this.width / 2 - this.textWidget.getWidth() / 2, this.height / 2 - 24);
        }
        if (this.cancelButton != null) {
            this.cancelButton.setPosition(this.width / 2 - this.cancelButton.getWidth() / 2, this.height / 2 + 4);
        }
    }

    /** Must be called on the client thread. */
    public void setMessage(Component message) {
        this.message = message;
        if (this.textWidget != null) {
            this.textWidget.setMessage(message);
            this.repositionElements();
        }
    }

    /** Safe to poll from a background thread. */
    public boolean isCancelled() {
        return this.cancelled.get();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected boolean shouldNarrateNavigation() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        this.extractPanorama(graphics, a);
        this.extractBlurredBackground(graphics);
        this.extractMenuBackground(graphics);
    }
}
