package dev.codex.longrange.mixin;

import dev.codex.longrange.client.RemoteAvatarRenderer;
import java.util.UUID;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional Emotecraft 3.4 lookup fallback. Existing world players and its network transport are untouched. */
@Pseudo
@Mixin(targets = "io.github.kosmx.emotes.PlatformTools", remap = false)
public abstract class EmotecraftLookupMixin {
    @Inject(method = "getAvatarFromUUID", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void longRangePlayers$findAvatar(UUID uuid, CallbackInfoReturnable<Avatar> cir) {
        if (cir.getReturnValue() == null) {
            Avatar avatar = RemoteAvatarRenderer.emoteAvatar(uuid);
            if (avatar != null) cir.setReturnValue(avatar);
        }
    }
}
