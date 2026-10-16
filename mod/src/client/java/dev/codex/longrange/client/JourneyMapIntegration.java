package dev.codex.longrange.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.common.Context;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.BlockPos;
import org.slf4j.LoggerFactory;

/** Only JourneyMap loads this optional entrypoint; no API classes are bundled in our JAR. */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapIntegration implements IClientPlugin {
    private final Map<UUID, MarkerOverlay> markers = new HashMap<>();
    private IClientAPI api;
    private Object lastLevel;
    private int ticks;
    private boolean warned;
    @Override public String getModId() { return "long_range_players"; }
    @Override public void initialize(IClientAPI api) {
        this.api = api;
        LongRangePlayersClient.registerMapReset(this::clearWorld);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    private void clearWorld() {
        try { api.removeAll(getModId()); }
        catch (Exception error) {
            LoggerFactory.getLogger("long_range_players").warn("JourneyMap clear failed ({})", error.getClass().getSimpleName());
        }
        markers.clear(); lastLevel = null; ticks = 0;
    }
    private void tick(Minecraft client) {
        if (++ticks % 5 != 0) return;
        var connection = LongRangePlayersClient.connection();
        try {
            if (lastLevel != client.level || client.level == null || connection == null || !connection.config().enabled || !connection.config().journeyMap) {
                api.removeAll(getModId()); markers.clear(); lastLevel = client.level;
                if (client.level == null || connection == null || !connection.config().enabled || !connection.config().journeyMap) return;
            }
            HashSet<UUID> live = new HashSet<>();
            for (var peer : connection.players().values()) {
                // Omitting a sneaking peer from live also removes its existing marker below.
                // The 3D avatar and /lrp list keep their independent visibility rules.
                if (peer.flag("sneaking") || !RemoteAvatarRenderer.eligible(client, peer)) continue;
                live.add(peer.uuid());
                var info = client.getConnection() == null ? null : client.getConnection().getPlayerInfo(peer.uuid());
                var skin = info == null ? DefaultPlayerSkin.get(peer.uuid()) : info.getSkin();
                MapImage icon = new MapImage(skin.body().texturePath(), 8, 8, 8, 8, 0xFFFFFF, 1F)
                        .setDisplayWidth(12).setDisplayHeight(12).centerAnchors();
                BlockPos point = BlockPos.containing(peer.x(), peer.y(), peer.z());
                MarkerOverlay marker = markers.computeIfAbsent(peer.uuid(), uuid -> new MarkerOverlay(getModId(), point, icon));
                marker.setPoint(point); marker.setIcon(icon);
                marker.setDimension(client.level.dimension()); marker.setLabel(peer.name());
                marker.setTitle(peer.name() + " (Long Range Players)");
                marker.setActiveUIs(Context.UI.Minimap, Context.UI.Fullscreen); marker.flagForRerender();
                api.show(marker);
            }
            var iterator = markers.entrySet().iterator();
            while (iterator.hasNext()) { var entry = iterator.next(); if (!live.contains(entry.getKey())) { api.remove(entry.getValue()); iterator.remove(); } }
            warned = false;
        } catch (Exception error) {
            if (!warned) LoggerFactory.getLogger("long_range_players").warn("JourneyMap update failed ({})", error.getClass().getSimpleName());
            warned = true;
        }
    }
}
