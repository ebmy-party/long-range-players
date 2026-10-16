package dev.codex.longrange.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class ConsentScreen {
    private static String launchPrompted = "";
    private static boolean reviewRequested;
    static String generalKey(Minecraft client) { return ConsentRules.generalKey(PrivacyPolicy.current().hash(), client.getUser().getProfileId()); }
    static void requestReview() { reviewRequested = true; }
    static void tick(Minecraft client) {
        if (!client.isGameLoadFinished() || client.gui.overlay() != null || ReplayRecording.isPlayback()) return;
        var config = LongRangePlayersClient.config();
        String key = generalKey(client);
        if (reviewRequested) { reviewRequested = false; open(client); return; }
        boolean readyScreen = client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                || (client.gui.screen() == null && client.player != null);
        if (readyScreen && !launchPrompted.equals(key) && !ConsentRules.decided(config.privacyDecision, key)) open(client);
    }
    static void open(Minecraft client) {
        if (ReplayRecording.isPlayback()) return;
        String general = generalKey(client);
        launchPrompted = general;
        ClientConfig draft = LongRangePlayersClient.config().copy();
        boolean[] agrees = { false };
        ConfigBuilder builder = ConfigBuilder.create().setParentScreen(client.gui.screen())
                .setTitle(Component.literal("LR-Players：利用開始の確認"));
        var category = builder.getOrCreateCategory(Component.literal("外部送信について"));
        var entries = builder.entryBuilder();
        PrivacyDisclosure.add(category, entries);
        GroupCodeScreen.add(category, entries, draft);
        category.addEntry(entries.startBooleanToggle(Component.literal("上記の外部送信に同意して利用する"), false)
                .setYesNoTextSupplier(value -> Component.literal(value ? "はい" : "いいえ"))
                .setDefaultValue(false).setSaveConsumer(value -> agrees[0] = value).build());
        category.addEntry(entries.startTextDescription(Component.literal("「はい」を選んで保存すると利用を開始します。コードは後から /lrp code でも設定できます。「いいえ」やキャンセルでは通信を開始しません。")).build());
        builder.setSavingRunnable(() -> {
            if (!general.equals(generalKey(client))) return;
            ClientConfig next = LongRangePlayersClient.config().copy();
            next.groupCode = draft.groupCode;
            ConsentRules.record(next, general, null, PrivacyPolicy.current().hash(), agrees[0]);
            if (!agrees[0]) LongRangePlayersClient.applyConfig(next);
            try { next.save(); LongRangePlayersClient.applyConfig(next); }
            catch (RuntimeException error) { LongRangePlayersClient.connection().reportConfigError(); }
        });
        client.gui.setScreen(builder.build());
    }
}
