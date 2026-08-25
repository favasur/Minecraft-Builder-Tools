package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: creative player abilities (flight speed multiplier, no-clip, flying).
 * Any field left at its "skip" sentinel is not touched.
 */
public record PlayerAbilitiesPacket(float flySpeed, Boolean noClip, Boolean fly)
        {
    public static final float SKIP_SPEED = -1.0f;

    public static PlayerAbilitiesPacket decode(FriendlyByteBuf buf) {
return new PlayerAbilitiesPacket(
        buf.readFloat(),
        buf.readBoolean() ? buf.readBoolean() : null,
        buf.readBoolean() ? buf.readBoolean() : null);
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeFloat(flySpeed());
buf.writeBoolean(noClip() != null);
if (noClip() != null) {
    buf.writeBoolean(noClip());
}
buf.writeBoolean(fly() != null);
if (fly() != null) {
    buf.writeBoolean(fly());
}
    }

    public static void handle(PlayerAbilitiesPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.applyPlayerAbilities(serverPlayer,
                        payload.flySpeed(), payload.noClip(), payload.fly());
            }
        });
    }
}
