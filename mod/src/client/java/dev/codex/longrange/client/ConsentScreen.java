package dev.codex.longrange.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConsentScreen extends CenteredTextScreen {
    private static String launchPrompted = "";
    private static boolean reviewRequested;
    private final Screen parent;
    private final String general;
    private boolean leaving;
    private ConsentScreen(Screen parent, String general) {
        super("LR-Players：利用開始の確認", PrivacyDisclosure.lines());
        this.parent = parent; this.general = general;
    }
    static String generalKey(Minecraft client) { return ConsentRules.generalKey(PrivacyPolicy.current().hash(), client.getUser().getProfileId()); }
    static void requestReview() { reviewRequested = true; }
    static void tick(Minecraft client) {
        if (!client.isGameLoadFinished() || client.gui.overlay() != null || ReplayRecording.isPlayback()) return;
        String key = generalKey(client);
        if (reviewRequested) { reviewRequested = false; open(client); return; }
        boolean ready = client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                || (client.gui.screen() == null && client.player != null);
        if (ready && !key.equals(launchPrompted) && !key.equals(LongRangePlayersClient.config().privacyDecision)) open(client);
    }
    static void open(Minecraft client) {
        if (ReplayRecording.isPlayback()) return;
        launchPrompted = generalKey(client);
        client.gui.setScreen(new ConsentScreen(client.gui.screen(), launchPrompted));
    }
    @Override protected void init() {
        super.init();
        int buttonWidth = Math.min(170, (width - 36) / 2);
        addRenderableWidget(Button.builder(Component.literal("同意する"), button -> accept())
                .bounds(width / 2 - buttonWidth - 4, height - 30, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("同意しない"), button -> decline())
                .bounds(width / 2 + 4, height - 30, buttonWidth, 20).build());
    }
    private void accept() {
        if (leaving) return;
        if (!general.equals(generalKey(minecraft))) { error = "アカウントが変更されました。画面を開き直してください。"; return; }
        var next = LongRangePlayersClient.config().copy();
        ConsentRules.record(next, general, null, PrivacyPolicy.current().hash(), true);
        try {
            next.save(); LongRangePlayersClient.applyConfig(next); leaving = true;
            minecraft.gui.setScreen(new GroupCodeScreen(parent));
        } catch (RuntimeException failure) { error = "同意を保存できません。設定ファイルの権限を確認してください。"; }
    }
    private void decline() {
        if (leaving) return;
        leaving = true;
        var next = LongRangePlayersClient.config().copy();
        ConsentRules.record(next, general, null, PrivacyPolicy.current().hash(), false);
        LongRangePlayersClient.applyConfig(next);
        try { next.save(); } catch (RuntimeException ignored) { }
        // Same graceful exit entry point as Minecraft's Quit Game button.
        minecraft.stop();
    }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void onClose() { }
}
