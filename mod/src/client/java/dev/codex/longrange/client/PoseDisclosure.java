package dev.codex.longrange.client;

import com.google.gson.JsonObject;
import java.util.function.Supplier;

/** Hidden state never constructs a coordinate/equipment payload or discloses the reason. */
final class PoseDisclosure {
    static JsonObject message(boolean invisible, boolean spectator, Supplier<JsonObject> visiblePose) {
        if (!invisible && !spectator) return visiblePose.get();
        JsonObject withdrawal = new JsonObject();
        withdrawal.addProperty("type", "withdraw");
        return withdrawal;
    }
}
