package dev.codex.longrange.mixin;

import dev.codex.longrange.client.ReplayRecording;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Order a synthetic despawn before ReplayMod saves the returning real player's spawn. */
@Pseudo
@Mixin(targets = "com.replaymod.recording.packet.PacketListener$DecodedPacketListener", remap = false)
public abstract class ReplayPacketBoundaryMixin {
    @Inject(method = "channelRead", at = @At("HEAD"), require = 0, remap = false)
    private void longRangePlayers$beforeRecordedPacket(ChannelHandlerContext context, Object packet, CallbackInfo ci) {
        ReplayRecording.beforeInbound(context, packet);
    }
}
