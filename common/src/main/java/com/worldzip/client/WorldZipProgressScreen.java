package com.worldzip.client;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import com.worldzip.archive.ArchiveProgress;
import com.worldzip.archive.ByteFormat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Shown while a zip/unzip runs in the background. Like vanilla's {@code GenericMessageScreen}, but
 * with a Cancel button and a byte progress bar.
 */
public final class WorldZipProgressScreen extends Screen {

    private static final int BAR_WIDTH = 200;
    private static final int BAR_HEIGHT = 6;
    private static final int BAR_BG = 0xFF3F3F3F;
    private static final int BAR_FG = 0xFF80C71F;
    private static final int BAR_OUTLINE = 0xFF000000;

    private final ArchiveProgress progress = new ArchiveProgress();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private @Nullable FocusableTextWidget textWidget;
    private @Nullable StringWidget percentWidget;
    private @Nullable Button cancelButton;
    private Component message;

    public WorldZipProgressScreen(Component message) {
        super(message);
        this.message = message;
    }

    public ArchiveProgress progress() {
        return this.progress;
    }

    @Override
    protected void init() {
        this.textWidget = this.addRenderableWidget(FocusableTextWidget.builder(this.message, this.font, 12).textWidth(this.font.width(this.message)).build());
        this.percentWidget = this.addRenderableWidget(new StringWidget(this.percentText(), this.font).setMaxWidth(220));
        this.cancelButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.cancelled.set(true)).build());
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (this.textWidget != null) {
            this.textWidget.setPosition(this.width / 2 - this.textWidget.getWidth() / 2, this.height / 2 - 40);
        }
        if (this.percentWidget != null) {
            this.percentWidget.setPosition(this.width / 2 - this.percentWidget.getWidth() / 2, this.height / 2 - 8);
        }
        if (this.cancelButton != null) {
            this.cancelButton.setPosition(this.width / 2 - this.cancelButton.getWidth() / 2, this.height / 2 + 20);
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
    public void tick() {
        super.tick();
        if (this.percentWidget != null) {
            this.percentWidget.setMessage(this.percentText());
            this.percentWidget.setX(this.width / 2 - this.percentWidget.getWidth() / 2);
        }
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = this.width / 2 - BAR_WIDTH / 2;
        int y = this.height / 2 + 6;
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, BAR_OUTLINE);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, BAR_BG);
        int filled = Math.round(BAR_WIDTH * this.progress.fraction());
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + BAR_HEIGHT, BAR_FG);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        this.extractPanorama(graphics, a);
        this.extractBlurredBackground(graphics);
        this.extractMenuBackground(graphics);
    }

    private Component percentText() {
        long done = this.progress.done();
        long total = this.progress.total();
        if (total <= 0L) {
            return Component.translatable("worldzip.progress.working");
        }
        int percent = Math.round(this.progress.fraction() * 100f);
        return Component.literal(String.format(
            Locale.ROOT,
            "%d%%  ·  %s / %s",
            percent,
            ByteFormat.human(done),
            ByteFormat.human(total)
        ));
    }
}
