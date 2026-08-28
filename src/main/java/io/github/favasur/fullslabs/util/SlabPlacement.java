package io.github.favasur.fullslabs.util;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.util.EnumPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

public class SlabPlacement {
    public static Vec2 getLookingAtPosition(Direction blockFace, Direction playerFacing, BlockPos pos, Vec3 hit) {
        float x = (float)(hit.x - (double)pos.getX());
        float y = (float)(hit.y - (double)pos.getY());
        float z = (float)(hit.z - (double)pos.getZ());
        float posH = 0.0f;
        float posV = 0.0f;
        switch (blockFace) {
            case DOWN: 
            case UP: {
                switch (playerFacing) {
                    case NORTH: {
                        posH = x;
                        posV = 1.0f - z;
                        break;
                    }
                    case SOUTH: {
                        posH = 1.0f - x;
                        posV = z;
                        break;
                    }
                    case WEST: {
                        posH = 1.0f - z;
                        posV = 1.0f - x;
                        break;
                    }
                    case EAST: {
                        posH = z;
                        posV = x;
                        break;
                    }
                }
                if (blockFace != Direction.DOWN) break;
                posV = 1.0f - posV;
                break;
            }
            case NORTH: 
            case SOUTH: {
                posH = blockFace.getAxisDirection() == Direction.AxisDirection.POSITIVE ? x : 1.0f - x;
                posV = y;
                break;
            }
            case WEST: 
            case EAST: {
                posH = blockFace.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? z : 1.0f - z;
                posV = y;
            }
        }
        return new Vec2(posH, posV);
    }

    public static Direction getTargetedDirection(Mode mode, Direction face, Direction facing, BlockPos pos, Vec3 hit) {
        Vec2 position = SlabPlacement.getLookingAtPosition(face, facing, pos, hit);
        return switch (mode.ordinal()) {
            default -> throw new MatchException(null, null);
            case 0 -> SlabPlacement.getTargetedDirectionHybrid(face, facing, position);
            case 1 -> SlabPlacement.getTargetedDirectionVanilla(face, position.y);
            case 2 -> SlabPlacement.getTargetedDirectionVertical(face, facing, position);
        };
    }

    private static Direction getTargetedDirectionHybrid(Direction face, Direction facing, Vec2 position) {
        float posH = position.x;
        float posV = position.y;
        float offH = Math.abs(posH - 0.5f);
        float offV = Math.abs(posV - 0.5f);
        if (face.getAxis().isVertical()) {
            // Horizontal face: the near/far strip (toward/away from the player) wins, so the slab
            // hugs the edge facing the player; left/right only in the middle band; the center
            // places a plain horizontal slab.
            if (offV > 0.25) {
                if (face == Direction.DOWN) {
                    return posV > 0.5f ? facing.getOpposite() : facing;
                }
                return posV < 0.5f ? facing.getOpposite() : facing;
            }
            if (offH > 0.25) {
                return posH < 0.5f ? facing.getCounterClockWise() : facing.getClockWise();
            }
            return face == Direction.DOWN ? Direction.UP : Direction.DOWN;
        }
        // Vertical face: unchanged edge regions (left/right first, then top/bottom); the center
        // keeps the "face" target so the overlay still shows the central region, and
        // SlabVertical#getTargetedState turns that into a plain horizontal slab.
        if ((double)offH > 0.25 || (double)offV > 0.25) {
            if (offH > offV) {
                return posH < 0.5f ? face.getClockWise() : face.getCounterClockWise();
            }
            return posV < 0.5f ? Direction.DOWN : Direction.UP;
        }
        return face;
    }

    private static Direction getTargetedDirectionVanilla(Direction face, float y) {
        if (face == Direction.DOWN) {
            return Direction.UP;
        }
        if (face == Direction.UP) {
            return Direction.DOWN;
        }
        return (double)y > 0.5 ? Direction.UP : Direction.DOWN;
    }

    private static Direction getTargetedDirectionVertical(Direction face, Direction facing, Vec2 position) {
        float posH = position.x;
        float posV = position.y;
        float offH = Math.abs(posH - 0.5f);
        float offV = Math.abs(posV - 0.5f);
        if (face.getAxis().isVertical()) {
            // Same player-relative edges as hybrid mode: near/far strip first, then left/right in
            // the middle band, then the center, which places a plain horizontal slab.
            if (offV > 0.25) {
                if (face == Direction.DOWN) {
                    return posV > 0.5f ? facing.getOpposite() : facing;
                }
                return posV < 0.5f ? facing.getOpposite() : facing;
            }
            if (offH > 0.25) {
                return posH < 0.5f ? facing.getCounterClockWise() : facing.getClockWise();
            }
            return face == Direction.DOWN ? Direction.UP : Direction.DOWN;
        }
        // Vertical face: vertical slabs hugging the clicked side on the edges; the center keeps
        // the "face" target so SlabVertical#getTargetedState places a plain horizontal slab.
        if (offH > 0.25 || offV > 0.25) {
            return position.x < 0.5f ? face.getClockWise() : face.getCounterClockWise();
        }
        return face;
    }

    public static enum Mode implements EnumPayload<Mode>
    {
        HYBRID,
        VANILLA,
        VERTICAL;

        public static final CustomPacketPayload.Type<Mode> PACKET_TYPE;
        public static final StreamCodec<RegistryFriendlyByteBuf, Mode> PACKET_CODEC;

        public Mode next() {
            return switch (this.ordinal()) {
                default -> throw new MatchException(null, null);
                case 0 -> VANILLA;
                case 1 -> VERTICAL;
                case 2 -> HYBRID;
            };
        }

        @NotNull
        public CustomPacketPayload.Type<Mode> type() {
            return PACKET_TYPE;
        }

        static {
            PACKET_TYPE = new CustomPacketPayload.Type(FullSlabs.id("mode"));
            PACKET_CODEC = EnumPayload.codecOf(Mode.class);
        }
    }
}

