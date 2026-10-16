package dev.codex.longrange.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

final class LongRangePlayersConfigScreen extends Screen {
    private final Screen parent;
    private ClientConfig draft;
    private EditBox distance, code;
    private String error = "", distanceDraft;
    private int distanceY, codeY;
    private LongRangePlayersConfigScreen(Screen parent) {
        super(Component.literal("LR-Players：設定")); this.parent = parent;
        draft = LongRangePlayersClient.config().copy();
        distanceDraft = Integer.toString(draft.minDistanceChunks);
    }
    static Screen create(Screen parent) { return new LongRangePlayersConfigScreen(parent); }
    void refreshGroupCode() { draft.groupCode = LongRangePlayersClient.config().groupCode; }
    private String toggleLabel(String label, boolean value) { return label + "：" + (value ? "有効" : "無効"); }
    @Override protected void init() {
        int w = Math.min(300, width - 36), x = (width - w) / 2;
        int y = Math.max(32, (height - 176) / 2);
        addRenderableWidget(Button.builder(Component.literal(toggleLabel("MODの動作", draft.enabled)), button -> {
            draft.enabled = !draft.enabled; button.setMessage(Component.literal(toggleLabel("MODの動作", draft.enabled)));
        }).bounds(x, y, w, 20).build()); y += 24;
        addRenderableWidget(Button.builder(Component.literal(toggleLabel("デバッグ表示", draft.debug)), button -> {
            draft.debug = !draft.debug; button.setMessage(Component.literal(toggleLabel("デバッグ表示", draft.debug)));
        }).bounds(x, y, w, 20).build()); y += 24;
        addRenderableWidget(Button.builder(Component.literal(toggleLabel("JourneyMap表示", draft.journeyMap)), button -> {
            draft.journeyMap = !draft.journeyMap; button.setMessage(Component.literal(toggleLabel("JourneyMap表示", draft.journeyMap)));
        }).bounds(x, y, w, 20).build()); y += 24;
        distanceY = y; y += 12;
        distance = new EditBox(font, x, y, w, 20, Component.literal("表示開始距離（チャンク）"));
        distance.setMaxLength(2); distance.setCentered(true); distance.setValue(distanceDraft);
        distance.setResponder(value -> distanceDraft = value);
        addRenderableWidget(distance); y += 24;
        codeY = y; y += 12;
        code = new EditBox(font, x, y, w, 20, Component.literal("グループコード"));
        code.setMaxLength(64); code.setCentered(true); code.setValue(draft.groupCode);
        code.setResponder(value -> draft.groupCode = value);
        code.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        addRenderableWidget(code);
        int half = Math.min(150, (width - 36) / 2);
        addRenderableWidget(Button.builder(Component.literal("説明を確認"), button -> ConsentScreen.open(minecraft))
                .bounds(width / 2 - half - 4, height - 30, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("保存して戻る"), button -> save())
                .bounds(width / 2 + 4, height - 30, half, 20).build());
    }
    private void save() {
        try {
            String text = distance.getValue();
            if (!text.matches("[0-9]{1,2}")) throw new IllegalArgumentException();
            int value = Integer.parseInt(text);
            if (value > 64) throw new IllegalArgumentException();
            draft.minDistanceChunks = value;
        } catch (RuntimeException invalid) { error = "距離は0〜64チャンクで入力してください。"; return; }
        if (!code.getValue().isEmpty() && !ClientConfig.validCode(code.getValue())) { error = "コードは英字と数字を含む8〜64文字です。"; return; }
        var next = LongRangePlayersClient.config().copy();
        next.enabled = draft.enabled; next.debug = draft.debug; next.journeyMap = draft.journeyMap;
        next.minDistanceChunks = draft.minDistanceChunks; next.groupCode = code.getValue();
        try { next.save(); LongRangePlayersClient.applyConfig(next); onClose(); }
        catch (RuntimeException failure) { error = "保存できません。設定ファイルを確認してください。"; }
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        // Screen extracts the background before invoking this method (Minecraft 26.2).
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.centeredText(font, "第二の体の表示開始距離（チャンク）", width / 2, distanceY, 0xFFE0E0E0);
        graphics.centeredText(font, "グループコード", width / 2, codeY, 0xFFE0E0E0);
        if (!error.isEmpty()) graphics.centeredText(font, error, width / 2, height - 44, 0xFFFF8080);
        super.extractRenderState(graphics, x, y, delta);
    }
}
