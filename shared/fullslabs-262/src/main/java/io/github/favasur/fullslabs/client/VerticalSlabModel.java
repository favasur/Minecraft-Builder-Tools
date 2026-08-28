package io.github.favasur.fullslabs.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The model for a vertical slab state (26.2). Since a vertical slab is the same {@link
 * SlabBlock} as its horizontal form, this model delegates to the block's own model for the
 * bottom, non-vertical state and re-emits its quads rotated 90 degrees onto the slab's edge,
 * exactly like the 1.21.1 JSON templates did. Because the source model is the block's real
 * baked model, this works for every slab in the game - vanilla or modded - with no resource
 * files and no per-slab registry entries.
 */
public final class VerticalSlabModel implements BlockStateModel {
    private static final Logger LOG = LogManager.getLogger("FullSlabs");
    private final BlockStateModel parent;
    private final Direction occupied;
    private final Matrix4f transform;
    /** Parent cull face -> transformed cull face (forward rotation). */
    private final Map<Direction, Direction> forward;
    /** Requested cull face -> parent cull face (inverse rotation). */
    private final Map<Direction, Direction> inverse;

    public VerticalSlabModel(BlockStateModel parent, Direction occupied) {
        this.parent = parent;
        this.occupied = occupied;
        Quaternionf rotation = new Quaternionf();
        Vector3f translation = new Vector3f();
        switch (occupied) {
            case NORTH -> {
                rotation.rotationX(-(float) Math.PI / 2.0F);
                translation.set(0.0F, 0.0F, 8.0F);
            }
            case SOUTH -> {
                rotation.rotationX((float) Math.PI / 2.0F);
                translation.set(0.0F, 16.0F, 8.0F);
            }
            case WEST -> {
                rotation.rotationZ((float) Math.PI / 2.0F);
                translation.set(8.0F, 0.0F, 0.0F);
            }
            case EAST -> {
                rotation.rotationZ(-(float) Math.PI / 2.0F);
                translation.set(8.0F, 16.0F, 0.0F);
            }
            default -> throw new IllegalArgumentException("Invalid vertical direction " + occupied);
        }
        // M = T * R so that p' = R(p) + t
        this.transform = new Matrix4f().translate(translation).rotate(rotation);
        this.forward = directionMap(rotation);
        this.inverse = directionMap(rotation.conjugate(new Quaternionf()));
        LOG.debug("Vertical slab model built: occupied={} translation={} forward={} inverse={}",
                occupied, translation, forward, inverse);
    }

    private static Map<Direction, Direction> directionMap(Quaternionf rotation) {
        Map<Direction, Direction> map = new EnumMap<>(Direction.class);
        for (Direction from : Direction.values()) {
            Vector3f v = new Vector3f(from.step()).rotate(rotation);
            int x = Math.round(v.x());
            int y = Math.round(v.y());
            int z = Math.round(v.z());
            for (Direction to : Direction.values()) {
                Vector3f step = to.step();
                if (Math.round(step.x()) == x && Math.round(step.y()) == y && Math.round(step.z()) == z) {
                    map.put(from, to);
                    break;
                }
            }
        }
        return map;
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        List<BlockStateModelPart> parts = new ArrayList<>();
        this.parent.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
            output.add(new VerticalSlabPart(part, this));
        }
    }

    @Override
    public Material.Baked particleMaterial() {
        return this.parent.particleMaterial();
    }

    @Override
    public @BakedQuad.MaterialFlags int materialFlags() {
        return this.parent.materialFlags();
    }

    private BakedQuad transformQuad(BakedQuad quad) {
        Vector3f p0 = new Vector3f(quad.position0()).mulPosition(this.transform);
        Vector3f p1 = new Vector3f(quad.position1()).mulPosition(this.transform);
        Vector3f p2 = new Vector3f(quad.position2()).mulPosition(this.transform);
        Vector3f p3 = new Vector3f(quad.position3()).mulPosition(this.transform);
        Direction direction = quad.direction() == null ? null : this.forward.get(quad.direction());
        return new BakedQuad(p0, p1, p2, p3,
                quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
                direction, quad.materialInfo());
    }

    private static final class VerticalSlabPart implements BlockStateModelPart {
        private final BlockStateModelPart parent;
        private final VerticalSlabModel model;

        private VerticalSlabPart(BlockStateModelPart parent, VerticalSlabModel model) {
            this.parent = parent;
            this.model = model;
        }

        private long lastQuadLog = 0L;

        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            List<BakedQuad> out;
            if (direction == null) {
                out = this.parent.getQuads(null).stream().map(this.model::transformQuad).toList();
            } else {
                Direction parentDirection = this.model.inverse.get(direction);
                out = this.parent.getQuads(parentDirection).stream().map(this.model::transformQuad).toList();
            }
            if (LOG.isDebugEnabled()) {
                long now = net.minecraft.Util.getMillis();
                if (now - this.lastQuadLog > 1000L) {
                    this.lastQuadLog = now;
                    // "No visible textures" symptom: faces come out empty when a mapped parent
                    // direction yields no quads or the transform collapses them - log the mapping
                    // and count for each requested cull face to pin which face is missing.
                    LOG.debug("Vertical slab part: cullFace={} -> parentCull={} produced {} quads (occupied={})",
                            direction,
                            direction == null ? null : this.model.inverse.get(direction),
                            out.size(),
                            this.model.occupied);
                }
            }
            return out;
        }

        @Override
        public boolean useAmbientOcclusion() {
            return this.parent.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return this.parent.particleMaterial();
        }

        @Override
        public @BakedQuad.MaterialFlags int materialFlags() {
            return this.parent.materialFlags();
        }
    }
}
