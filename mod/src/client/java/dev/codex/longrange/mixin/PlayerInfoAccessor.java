package dev.codex.longrange.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractClientPlayer.class)
public interface PlayerInfoAccessor {
    @Accessor("playerInfo")
    void longRangePlayers$setPlayerInfo(PlayerInfo info);
}
