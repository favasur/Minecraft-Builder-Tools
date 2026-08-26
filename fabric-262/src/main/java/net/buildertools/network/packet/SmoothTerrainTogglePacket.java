package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Server -> Client: the Smooth Terrain world setting changed. The client applies it to the bundled
 * Smooth Terrain config (visuals + collisions) and re-meshes every chunk.
 */
public record SmoothTerrainTogglePacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<SmoothTerrainTogglePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MODID, "smooth_terrain_toggle"));

    public static final StreamCodec<FriendlyByteBuf, SmoothTerrainTogglePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SmoothTerrainTogglePacket decode(FriendlyByteBuf buf) {
            return new SmoothTerrainTogglePacket(buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, SmoothTerrainTogglePacket packet) {
            buf.writeBoolean(packet.enabled());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SmoothTerrainTogglePacket payload, IPayloadContext context) {
        context.enqueueWork(() ->
                io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl.Server.setEnabled(payload.enabled()));
    }
}
