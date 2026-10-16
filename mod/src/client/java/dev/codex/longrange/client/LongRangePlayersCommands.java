package dev.codex.longrange.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

final class LongRangePlayersCommands {
    private LongRangePlayersCommands() { }
    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("lrp")
                        .executes(command -> showList(command.getSource()))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("code")
                                .executes(command -> { net.minecraft.client.Minecraft.getInstance().schedule(GroupCodeScreen::open); return 1; }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("privacy")
                                .executes(command -> { net.minecraft.client.Minecraft.getInstance().schedule(() -> ConsentScreen.open(net.minecraft.client.Minecraft.getInstance())); return 1; }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("revoke")
                                .executes(command -> revoke(command.getSource())))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("world")
                                .executes(command -> showWorld(command.getSource())))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
                                .executes(command -> showList(command.getSource())))));
    }
    private static int revoke(FabricClientCommandSource source) {
        var client = net.minecraft.client.Minecraft.getInstance();
        String context = ConsentScreen.context(client);
        var config = LongRangePlayersClient.config().copy();
        config.enabled = false;
        config.privacyDecision = "declined:" + ConsentScreen.generalKey(client);
        config.consents.clear();
        if (context != null && (config.consents.size() < 64 || config.consents.containsKey(context)))
            config.consents.put(context, "declined:" + PrivacyPolicy.current().hash());
        LongRangePlayersClient.applyConfig(config);
        try { config.save(); } catch (RuntimeException error) { LongRangePlayersClient.connection().reportConfigError(); }
        source.sendFeedback(Component.literal("[Long Range Players] 同意を撤回し、送信を停止しました。再開は /lrp privacy。"));
        return 1;
    }
    private static int showWorld(FabricClientCommandSource source) {
        ReplayRecording.updatePlayback();
        if (ReplayRecording.isPlayback()) {
            source.sendFeedback(Component.literal("[Long Range Players] リプレイ再生中はRelayへ接続しません。"));
            return 0;
        }
        var world = WorldIdentity.current();
        if (world == null) {
            source.sendError(Component.literal("[Long Range Players] ワールド情報をまだ受信していません。"));
            return 0;
        }
        source.sendFeedback(Component.literal("[Long Range Players] ワールド分離: ON / world=" + world.shortId() + " / " + world.dimension()));
        source.sendFeedback(Component.literal("グループコード・Minecraft接続先と、このworld値で分離します。同じ通知値の別ワールドは識別できません。"));
        return 1;
    }
    private static int showList(FabricClientCommandSource source) {
        PresenceConnection connection = LongRangePlayersClient.connection();
        if (connection == null || !connection.isOnline()) {
            source.sendError(Component.literal("[Long Range Players] Relay未接続のため一覧を取得できません。"
                    + (connection == null ? "" : " " + connection.statusText())));
            return 0;
        }
        var members = connection.members();
        source.sendFeedback(Component.literal("[Long Range Players] 同じ部屋の本人確認済み接続者: " + members.size()
                + "人（自分を含む・全ディメンション）"));
        for (var member : members) source.sendFeedback(Component.literal("・" + member.name()
                + (member.uuid().equals(source.getPlayer().getUUID()) ? "（自分）" : "")));
        return members.size();
    }
}
