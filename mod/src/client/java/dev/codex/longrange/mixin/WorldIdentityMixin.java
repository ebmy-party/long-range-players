package dev.codex.longrange.mixin;

import dev.codex.longrange.client.LongRangePlayersClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class WorldIdentityMixin {
    // TAIL runs on the client thread after vanilla's packet thread guard.
    // Capture every respawn: proxy transfers can keep the same ClientLevel/dimension.
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void longRangePlayers$worldLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        var info = packet.commonPlayerSpawnInfo();
        LongRangePlayersClient.worldReceived(info.seed(), info.dimension().identifier().toString());
    }
    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void longRangePlayers$worldRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        var info = packet.commonPlayerSpawnInfo();
        LongRangePlayersClient.worldReceived(info.seed(), info.dimension().identifier().toString());
    }
}
