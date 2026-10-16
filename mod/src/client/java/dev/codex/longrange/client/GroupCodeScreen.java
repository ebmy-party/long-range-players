package dev.codex.longrange.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

final class GroupCodeScreen extends Screen {
    private final Screen parent;
    private EditBox code;
    private String draft, error = "";
    private java.util.List<FormattedCharSequence> intro;
    private int introY;
    GroupCodeScreen(Screen parent) {
        super(Component.literal("LR-Players：グループコード"));
        this.parent = parent; draft = LongRangePlayersClient.config().groupCode;
    }
    static void open() { var client = Minecraft.getInstance(); client.gui.setScreen(new GroupCodeScreen(client.gui.screen())); }
    @Override protected void init() {
        int fieldWidth = Math.min(300, width - 40);
        intro = font.split(Component.literal("仲間と同じコードを入力してください。8〜64文字の半角英数字（英字と数字の両方が必要）。大文字・小文字を区別します。空欄では接続しません。"), Math.min(480, width - 36));
        int contentHeight = intro.size() * 12 + 32;
        introY = Math.max(44, (height - 76 - contentHeight) / 2);
        int y = introY + intro.size() * 12 + 12;
        code = new EditBox(font, (width - fieldWidth) / 2, y, fieldWidth, 20, Component.literal("グループコード"));
        code.setMaxLength(64); code.setCentered(true); code.setValue(draft);
        code.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        code.setResponder(value -> { draft = value; error = ""; });
        addRenderableWidget(code); setInitialFocus(code);
        int buttonWidth = Math.min(150, (width - 36) / 2);
        addRenderableWidget(Button.builder(Component.literal("保存"), button -> save())
                .bounds(width / 2 - buttonWidth - 4, height - 30, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("後で設定する"), button -> onClose())
                .bounds(width / 2 + 4, height - 30, buttonWidth, 20).build());
    }
    private void save() {
        if (!draft.isEmpty() && !ClientConfig.validCode(draft)) { error = "コードは英字と数字を含む8〜64文字です。"; return; }
        var next = LongRangePlayersClient.config().copy(); next.groupCode = draft;
        try { next.save(); LongRangePlayersClient.applyConfig(next); onClose(); }
        catch (RuntimeException failure) { error = "保存できません。設定ファイルを確認してください。"; }
    }
    @Override public void onClose() {
        if (parent instanceof LongRangePlayersConfigScreen settings) settings.refreshGroupCode();
        minecraft.gui.setScreen(parent);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        // Screen extracts the background before invoking this method (Minecraft 26.2).
        graphics.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
        int lineY = introY;
        for (var line : intro) {
            graphics.centeredText(font, line, width / 2, lineY, 0xFFE0E0E0); lineY += 12;
        }
        graphics.centeredText(font, "あとで /lrp code から設定できます。", width / 2, height - 65, 0xFFA0A0A0);
        if (!error.isEmpty()) graphics.centeredText(font, error, width / 2, height - 49, 0xFFFF8080);
        super.extractRenderState(graphics, x, y, delta);
    }
}
