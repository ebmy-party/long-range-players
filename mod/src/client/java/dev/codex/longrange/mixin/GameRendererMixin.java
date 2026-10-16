package dev.codex.longrange.mixin;

import dev.codex.longrange.client.RemoteAvatarRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "close", at = @At("RETURN"))
    private void longRangePlayers$closeBuffer(CallbackInfo ci) {
        RemoteAvatarRenderer.close();
    }
}
