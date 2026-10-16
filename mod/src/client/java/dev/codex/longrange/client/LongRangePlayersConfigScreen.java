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
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Long Range Players"))
                .setSavingRunnable(() -> {
                    try {
                        ClientConfig checked = config.copy();
                        boolean requestConsent = checked.enabled && !checked.privacyDecision.equals(ConsentScreen.generalKey(net.minecraft.client.Minecraft.getInstance()));
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
        general.addEntry(entries.startTextDescription(Component.literal("未同意の場合は、有効化して保存すると説明を確認できます。同意済みなら設定の変更だけで再確認はしません。")).build());
        general.addEntry(entries.startBooleanToggle(Component.literal("遠距離プレイヤー表示を有効化"), config.enabled)
                .setDefaultValue(false)
                .setSaveConsumer(value -> config.enabled = value)
                .build());
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
