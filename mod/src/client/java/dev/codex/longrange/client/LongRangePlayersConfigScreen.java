package dev.codex.longrange.client;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LongRangePlayersConfigScreen {
    private LongRangePlayersConfigScreen() { }

    static Screen create(Screen parent) {
        ClientConfig config = LongRangePlayersClient.config().copy();
        boolean wasEnabled = config.enabled;
        String previousPolicy = PrivacyPolicy.of(config).hash();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Long Range Players"))
                .setSavingRunnable(() -> {
                    try {
                        ClientConfig checked = config.copy();
                        boolean requestConsent = checked.enabled && (!wasEnabled || !previousPolicy.equals(PrivacyPolicy.of(checked).hash()));
                        if (requestConsent) checked.enabled = false;
                        checked.save();
                        LongRangePlayersClient.applyConfig(checked);
                        if (requestConsent) ConsentScreen.requestReview();
                    } catch (RuntimeException error) {
                        if (LongRangePlayersClient.connection() != null) LongRangePlayersClient.connection().reportConfigError();
                    }
                });
        ConfigCategory general = builder.getOrCreateCategory(Component.literal("接続設定"));
        ConfigEntryBuilder entries = builder.entryBuilder();

        PrivacyDisclosure.add(general, entries);
        GroupCodeScreen.add(general, entries, config);
        general.addEntry(entries.startTextDescription(Component.literal("無効状態から有効化すると、保存後に同意画面を表示します。以前「いいえ」を選んでも、ここから説明を確認して再開できます。")).build());
        general.addEntry(entries.startBooleanToggle(Component.literal("遠距離プレイヤー表示を有効化"), config.enabled)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.enabled = value)
                .build());
        general.addEntry(entries.startStrField(Component.literal("Relay URL（wss://…/ws）"), config.relayUrl)
                .setDefaultValue(ClientConfig.DEFAULT_RELAY_URL).setSaveConsumer(value -> config.relayUrl = value)
                .setErrorSupplier(value -> {
                    try { PrivacyPolicy.normalizeUrl(value); return java.util.Optional.empty(); }
                    catch (RuntimeException error) { return java.util.Optional.of(Component.literal("wss://ホスト名/ws を指定してください。認証情報・クエリは不可です。")); }
                }).build());
        general.addEntry(entries.startStrField(Component.literal("Relay運営者（管理者の案内と一致させる）"), config.relayOperator)
                .setDefaultValue("").setSaveConsumer(value -> config.relayOperator = value).build());
        general.addEntry(entries.startStrField(Component.literal("問い合わせ先（管理者の案内と一致させる）"), config.relayContact)
                .setDefaultValue("").setSaveConsumer(value -> config.relayContact = value).build());
        general.addEntry(entries.startIntField(Component.literal("アクセスログ保存日数（管理者の案内と一致させる）"), config.relayRetentionDays)
                .setDefaultValue(7).setMin(1).setMax(30).setSaveConsumer(value -> config.relayRetentionDays = value).build());
        general.addEntry(entries.startTextDescription(Component.literal("送信先・運営者・問い合わせ先・保存日数を変えると再同意が必要です。保存日数はRelay側と照合されます。運営者・問い合わせ先は管理者の案内を確認してください。")).build());
        general.addEntry(entries.startTextDescription(Component.literal("送信内容と同意は /lrp privacy、撤回は /lrp revoke。未同意では有効化しても通信しません。")).build());
        general.addEntry(entries.startTextDescription(Component.literal("グループコード・Minecraft接続先・ワールド識別値で分離します。/lrp world で識別値を確認できます。")).build());
        general.addEntry(entries.startBooleanToggle(Component.literal("デバッグ表示（接続状態・失敗原因）"), config.debug)
                .setDefaultValue(false).setSaveConsumer(value -> config.debug = value).build());
        general.addEntry(entries.startIntField(Component.literal("第二の体の表示開始距離（チャンク）"), config.minDistanceChunks)
                .setDefaultValue(2).setMin(0).setMax(64).setSaveConsumer(value -> config.minDistanceChunks = value)
                .setTooltip(Component.literal("手動設定。通常のプレイヤーが存在する間は第二の体を表示しません。")) .build());
        general.addEntry(entries.startBooleanToggle(Component.literal("JourneyMapに遠距離プレイヤーを表示"), config.journeyMap)
                .setDefaultValue(true).setSaveConsumer(value -> config.journeyMap = value).build());
        return builder.build();
    }
}
