package dev.codex.longrange.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class ConsentScreen {
    private static String prompted = "", launchPrompted = "";
    private static boolean reviewRequested;
    static String generalKey(Minecraft client) { return ConsentRules.generalKey(PrivacyPolicy.current().hash(), client.getUser().getProfileId()); }
    static void requestReview() { reviewRequested = true; }
    static void tick(Minecraft client) {
        if (!client.isGameLoadFinished() || client.gui.overlay() != null || ReplayRecording.isPlayback()) return;
        var config = LongRangePlayersClient.config();
        String key = generalKey(client);
        if (reviewRequested) { reviewRequested = false; open(client); return; }
        boolean readyScreen = client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                || (client.gui.screen() == null && context(client) != null);
        if (readyScreen && !launchPrompted.equals(key) && !ConsentRules.decided(config.privacyDecision, key)) open(client);
    }
    static void reset() { prompted = ""; }
    static String context(Minecraft client) {
        var world = WorldIdentity.current();
        if (client.player == null || client.getCurrentServer() == null || world == null) return null;
        return RoomIdentity.hash(ConsentBinding.key(client.getCurrentServer().ip, world.fingerprint(), client.player.getUUID())
                + "\n" + LongRangePlayersClient.config().groupCode(client.getCurrentServer().ip));
    }
    static void maybePrompt(Minecraft client, ClientConfig config, String context) {
        if (!config.enabled || !generalKey(client).equals(config.privacyDecision)) return;
        String decision = config.consents.get(context);
        if (context == null || context.equals(prompted) || PrivacyPolicy.current().hash().equals(decision)
                || ("declined:" + PrivacyPolicy.current().hash()).equals(decision) || client.gui.screen() != null) return;
        open(client);
    }
    static void open(Minecraft client) {
        if (ReplayRecording.isPlayback()) return;
        String context = context(client);
        String general = generalKey(client);
        launchPrompted = general;
        prompted = context;
        String address = context == null ? "" : client.getCurrentServer().ip;
        boolean[] agrees = { false };
        ConfigBuilder builder = ConfigBuilder.create().setParentScreen(client.gui.screen())
                .setTitle(Component.literal("Long Range Players：外部送信への同意"));
        var category = builder.getOrCreateCategory(Component.literal("送信前に確認してください"));
        var entries = builder.entryBuilder();
        category.addEntry(entries.startTextDescription(Component.literal(context == null
                ? "初回起動時の確認です。実際の接続先・ワールドへの送信は、参加時にもう一度確認します。"
                : "対象：" + address + " / world=" + WorldIdentity.current().shortId())).build());
        PrivacyDisclosure.add(category, entries);
        category.addEntry(entries.startTextDescription(Component.literal("初期選択は「いいえ」です。「はい」を選んで保存すると同意します。「いいえ」で保存すると無効のままにします。キャンセル／Escは同意になりません。")).build());
        if (PrivacyPolicy.current().ready()) category.addEntry(entries.startBooleanToggle(Component.literal(context == null ? "説明を理解し、本MODの利用に同意する" : "内容を確認し、この接続先・ワールドでの送信に同意する"), false)
                .setYesNoTextSupplier(value -> Component.literal(value ? "はい" : "いいえ"))
                .setDefaultValue(false).setSaveConsumer(value -> agrees[0] = value).build());
        else category.addEntry(entries.startTextDescription(Component.literal("接続先・運営者・問い合わせ先をMod Menuの設定で入力してください。未設定では同意・送信できません。")).build());
        builder.setSavingRunnable(() -> {
            // Saving a screen opened in a previous server/world cannot authorize the new context.
            if (!general.equals(generalKey(client)) || !java.util.Objects.equals(context, context(client))) return;
            ClientConfig next = LongRangePlayersClient.config().copy();
            boolean accept = agrees[0] && PrivacyPolicy.current().ready();
            if (accept && context != null && next.consents.size() >= 64 && !next.consents.containsKey(context)) {
                client.player.sendSystemMessage(Component.literal("[LRP] 同意先が64件に達しています。設定の同意記録を整理してください。")); return;
            }
            ConsentRules.record(next, general, context, PrivacyPolicy.current().hash(), accept);
            // Refusal stops an existing connection even if persisting the decision fails.
            if (!accept) LongRangePlayersClient.applyConfig(next);
            try { next.save(); LongRangePlayersClient.applyConfig(next); }
            catch (RuntimeException error) { LongRangePlayersClient.connection().reportConfigError(); }
        });
        client.gui.setScreen(builder.build());
    }
}
