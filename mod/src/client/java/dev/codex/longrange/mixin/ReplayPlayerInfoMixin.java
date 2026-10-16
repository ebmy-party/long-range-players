package dev.codex.longrange.mixin;

import dev.codex.longrange.client.ReplayRecording;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ReplayPlayerInfoMixin {
    @Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"))
    private void longRangePlayers$refreshReplaySkin(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
        ReplayRecording.beforePlaybackProfiles((ClientPacketListener) (Object) this, packet);
    }
}
