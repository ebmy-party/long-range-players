package dev.codex.longrange.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

/** Centered, wrapped paragraphs with a fixed action area, including on small GUI scales. */
abstract class CenteredTextScreen extends Screen {
    private final String[] paragraphs;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private double scroll;
    protected String error = "";
    CenteredTextScreen(String title, String[] paragraphs) { super(Component.literal(title)); this.paragraphs = paragraphs; }
    @Override protected void init() {
        lines.clear();
        for (String text : paragraphs) {
            lines.addAll(font.split(Component.literal(text), Math.max(80, Math.min(620, width - 36))));
            lines.add(FormattedCharSequence.EMPTY);
        }
        scroll = Math.min(scroll, maxScroll());
    }
    private int top() { return 36; }
    private int bottom() { return Math.max(48, height - 76); }
    private int maxScroll() { return Math.max(0, lines.size() * 12 - (bottom() - top())); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.clamp(scroll - vertical * 24, 0, maxScroll()); return true;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        // Screen extracts the background before invoking this method (Minecraft 26.2).
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.enableScissor(12, top(), width - 12, bottom());
        int lineY = top() + Math.max(0, (bottom() - top() - lines.size() * 12) / 2) - (int)scroll;
        for (var line : lines) { graphics.centeredText(font, line, width / 2, lineY, 0xFFE0E0E0); lineY += 12; }
        graphics.disableScissor();
        if (maxScroll() > 0) graphics.centeredText(font, "マウスホイールで説明をスクロールできます", width / 2, height - 66, 0xFFA0A0A0);
        if (!error.isEmpty()) graphics.centeredText(font, error, width / 2, height - 51, 0xFFFF8080);
        super.extractRenderState(graphics, x, y, delta);
    }
    @Override public Component getNarrationMessage() { return Component.literal(title.getString() + "。" + String.join("。", paragraphs)); }
}
