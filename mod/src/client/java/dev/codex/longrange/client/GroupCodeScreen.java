package dev.codex.longrange.client;

import me.shedaniel.clothconfig2.api.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import java.util.Optional;

final class GroupCodeScreen {
    private GroupCodeScreen() { }
    static void add(ConfigCategory category, ConfigEntryBuilder entries, ClientConfig config) {
        category.addEntry(entries.startTextDescription(Component.literal("仲間と同じグループコードを入力してください。8〜64文字の半角英数字（英字と数字の両方が必要）。大文字・小文字を区別します。空欄では接続しません。")).build());
        var entry = entries.startStrField(Component.literal("グループコード"), config.groupCode)
                .setDefaultValue("")
                .setErrorSupplier(value -> value.isEmpty() || ClientConfig.validCode(value)
                        ? Optional.empty() : Optional.of(Component.literal("8〜64文字、半角英字と数字の両方が必要です")))
                .setSaveConsumer(value -> config.groupCode = value)
                .build();
        for (var child : entry.children()) if (child instanceof EditBox box) {
            box.setMaxLength(64);
            box.addFormatter((value, cursor) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        }
        category.addEntry(entry);
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
