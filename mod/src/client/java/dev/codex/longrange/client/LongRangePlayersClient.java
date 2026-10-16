package dev.codex.longrange.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

public final class LongRangePlayersClient implements ClientModInitializer {
    private static PresenceConnection connection;
    private static Runnable mapReset = () -> { };

    @Override public void onInitializeClient() {
        ClientConfig config = ClientConfig.load();
        connection = new PresenceConnection(config);
        LongRangePlayersCommands.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ReplayRecording.updatePlayback();
            ConsentScreen.tick(client);
            connection.tick(client);
            RemoteAvatarRenderer.tick(client);
            ReplayRecording.tick(client);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> connection.joined());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WorldIdentity.clear(); connection.left(); clearWorldDisplays();
        });
        RemoteAvatarRenderer.initialize(connection);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("long_range_players", "connection_status"),
                (graphics, deltaTracker) -> renderStatus(graphics));
    }

    static PresenceConnection connection() { return connection; }

    public static void worldReceived(long seed, String dimension) {
        if (WorldIdentity.capture(seed, dimension)) {
            if (connection != null) connection.worldChanged();
            clearWorldDisplays();
        }
    }

    // Registered only by JourneyMap's optional entrypoint; core never loads JourneyMap API types.
    static void registerMapReset(Runnable reset) { mapReset = reset; }
    static void clearWorldDisplays() { RemoteAvatarRenderer.clear(); mapReset.run(); }

    static ClientConfig config() { return connection == null ? ClientConfig.load() : connection.config(); }

    static void applyConfig(ClientConfig config) {
        if (connection != null) connection.applyConfig(config);
    }

    private static void renderStatus(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || connection == null || !connection.config().debug) return;
        String label = connection.statusText();
        int x = 6;
        int y = 6;
        graphics.fill(x - 3, y - 3, x + client.font.width(label) + 3, y + 11, 0x88000000);
        graphics.text(client.font, label, x, y, connection.statusColor());
    }
}
