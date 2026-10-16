package dev.codex.longrange.client;

import me.shedaniel.clothconfig2.api.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import java.security.SecureRandom;
import java.util.Optional;

final class GroupCodeScreen {
    private GroupCodeScreen() { }
    static String generate() {
        var random = new SecureRandom();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        String code;
        do {
            var text = new StringBuilder();
            for (int i = 0; i < 16; i++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            code = text.toString();
        } while (!ClientConfig.validCode(code));
        return code;
    }
    static void add(ConfigCategory category, ConfigEntryBuilder entries, ClientConfig config) {
        var server = Minecraft.getInstance().getCurrentServer();
        if (server == null) {
            category.addEntry(entries.startTextDescription(Component.literal("グループコードはMinecraftサーバー参加後に設定できます。/lrp code でも入力画面を開けます。")).build());
            return;
        }
        String scope = ClientConfig.codeKey(config.relayUrl, server.ip);
        category.addEntry(entries.startTextDescription(Component.literal("グループコード：8〜64文字の半角英数字、英字と数字の両方が必要です。大文字・小文字を区別します。空欄で削除・通信停止。現在のRelayとMinecraft接続先だけに保存します。Relay URL変更後は画面を開き直して設定してください。")).build());
        var entry = entries.startStrField(Component.literal("グループコード"), config.groupCodes.getOrDefault(scope, ""))
                .setDefaultValue("")
                .setErrorSupplier(value -> value.isEmpty() || ClientConfig.validCode(value)
                        ? Optional.empty() : Optional.of(Component.literal("8〜64文字、半角英字と数字の両方が必要です")))
                .setSaveConsumer(value -> { if (value.isEmpty()) config.groupCodes.remove(scope); else config.groupCodes.put(scope, value); })
                .build();
        for (var child : entry.children()) if (child instanceof EditBox box) {
            box.setMaxLength(64);
            box.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        }
        category.addEntry(entry);
        category.addEntry(entries.startBooleanToggle(Component.literal("保存時にランダムな16文字を生成してクリップボードへコピー"), false)
                .setDefaultValue(false).setSaveConsumer(value -> {
                    if (value) { String code = generate(); config.groupCodes.put(scope, code); Minecraft.getInstance().keyboardHandler.setClipboard(code); }
                }).build());
        category.addEntry(entries.startTextDescription(Component.literal("生成したコードはクリップボードから仲間へ共有できます。チャットやログには表示しません。端末には平文で保存されます。")).build());
    }
    static void open() {
        var client = Minecraft.getInstance();
        var config = LongRangePlayersClient.config().copy();
        var builder = ConfigBuilder.create().setParentScreen(client.gui.screen()).setTitle(Component.literal("Long Range Players：グループコード"));
        add(builder.getOrCreateCategory(Component.literal("グループ")), builder.entryBuilder(), config);
        builder.setSavingRunnable(() -> {
            try {
                var checked = config.copy(); checked.save(); LongRangePlayersClient.applyConfig(checked);
            } catch (RuntimeException error) { LongRangePlayersClient.connection().reportConfigError(); }
        });
        client.gui.setScreen(builder.build());
    }
}
