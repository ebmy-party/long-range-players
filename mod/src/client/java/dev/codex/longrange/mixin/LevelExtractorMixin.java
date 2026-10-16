package dev.codex.longrange.mixin;

import dev.codex.longrange.client.RemoteAvatarRenderer;
import dev.codex.longrange.client.ReplayRecording;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void longRangePlayers$replayVisibility(Entity entity, Frustum frustum, double x, double y, double z,
                                                   CallbackInfoReturnable<Boolean> ci) {
        // Vanilla requires a compiled chunk section; the relay can see beyond recorded terrain.
        if (ReplayRecording.isRecordedAvatar(entity))
            ci.setReturnValue(!entity.isInvisible() && frustum.isVisible(entity.getBoundingBox().inflate(0.5)));
    }
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void longRangePlayers$replayLight(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> ci) {
        var level = Minecraft.getInstance().level;
        if (ReplayRecording.isRecordedAvatar(entity) && level != null && !level.hasChunkAt(entity.blockPosition()))
            ci.getReturnValue().lightCoords = 0x00F000F0;
    }
    @Inject(method = "extractVisibleEntities", at = @At("TAIL"))
    private void longRangePlayers$extract(Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState output, CallbackInfo ci) {
        RemoteAvatarRenderer.extract(camera, frustum, delta, output);
    }
}
