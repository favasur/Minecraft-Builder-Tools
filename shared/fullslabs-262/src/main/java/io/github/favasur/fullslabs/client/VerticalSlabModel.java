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
import org.joml.Vector3fc;

/**
 * The model for a vertical slab state (26.2). Since a vertical slab is the same {@link
 * net.minecraft.world.level.block.SlabBlock} as its horizontal form, this model delegates to the
 * block's own model for the bottom, non-vertical state and re-emits its quads rotated 90 degrees
 * onto the slab's edge, exactly like the 1.21.1 JSON templates did. Because the source model is
 * the block's real baked model, this works for every slab in the game - vanilla or modded - with
 * no resource files and no per-slab registry entries.
 *
 * <p>Each emitted quad's cull face is derived from its own transformed geometry (the same
 * calculate-facing rule the block baker uses), never from the parent's cull-face buckets alone.
 * That matters because vanilla slab models bake the top face WITHOUT a cullface - it lives in the
 * un-culled batch - so a lookup of "parent faces culled to up" finds nothing and the vertical
 * slab face that corresponds to the parent's top face used to come out empty (the invisible
 * face). Re-deriving directions from geometry puts every one of the slab's six faces into its
 * proper cull bucket and never emits a null-direction quad.
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
                translation.set(0.0F, 0.0F, 0.5F);
            }
            case SOUTH -> {
                rotation.rotationX((float) Math.PI / 2.0F);
                translation.set(0.0F, 1.0F, 0.5F);
            }
            case WEST -> {
                rotation.rotationZ((float) Math.PI / 2.0F);
                translation.set(0.5F, 0.0F, 0.0F);
            }
            case EAST -> {
                rotation.rotationZ(-(float) Math.PI / 2.0F);
                translation.set(0.5F, 1.0F, 0.0F);
            }
            default -> throw new IllegalArgumentException("Invalid vertical direction " + occupied);
        }
        // M = T * R so that p' = R(p) + t. The baked quad positions are block-local 0..1
        // units (the layer renderer and RotatedBlockModel rotate around 0.5), so the
        // half-block translations are 0.5/1.0, NOT 8/16: using 8/16 placed the slab about
        // eight blocks away from its cell and made every vertical slab invisible.
        this.transform = new Matrix4f().translate(translation).rotate(rotation);
        this.forward = directionMap(rotation);
        this.inverse = directionMap(rotation.conjugate(new Quaternionf()));
        LOG.debug("Vertical slab model built: occupied={} translation={}", occupied, translation);
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
        // Derive the cull face from the transformed geometry instead of trusting the parent's
        // bucket metadata, so un-culled parent quads (the vanilla slab's top face) get a real
        // direction here.
        Direction direction = deriveDirection(p0, p1, p2);
        return new BakedQuad(p0, p1, p2, p3,
                quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
                direction, quad.materialInfo());
    }

    /**
     * The cull face of a quad derived from its vertex positions - the same rule the block baker
     * uses: normal = (v2 - v1) x (v0 - v1), then the direction with the strongest positive dot
     * product. Rotations preserve winding, so the transformed quad yields the correct outward
     * face.
     */
    private static Direction deriveDirection(Vector3fc p0, Vector3fc p1, Vector3fc p2) {
        float ax = p0.x() - p1.x(), ay = p0.y() - p1.y(), az = p0.z() - p1.z();
        float bx = p2.x() - p1.x(), by = p2.y() - p1.y(), bz = p2.z() - p1.z();
        float nx = by * az - bz * ay;
        float ny = bz * ax - bx * az;
        float nz = bx * ay - by * ax;
        float len = (float) Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
        if (!Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz) || len < 1.0E-6F) {
            return Direction.UP;
        }
        Direction best = Direction.UP;
        float bestDot = -1.0F;
        for (Direction direction : Direction.values()) {
            float dot = (nx * direction.getStepX() + ny * direction.getStepY() + nz * direction.getStepZ()) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = direction;
            }
        }
        return best;
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
            List<BakedQuad> out = new ArrayList<>();
            // Quads whose parent cull face rotates onto this slab face (forward rotation).
            if (direction != null) {
                Direction parentDirection = this.model.inverse.get(direction);
                for (BakedQuad quad : this.parent.getQuads(parentDirection)) {
                    out.add(this.model.transformQuad(quad));
                }
            }
            // The parent's un-culled batch: vanilla slab models bake the top face without a
            // cullface, so the inverse lookup above finds nothing for the slab face that
            // corresponds to the parent's top. After the 90-degree rotation that face is a real
            // side face - re-cull it from the transformed geometry and emit it into the right
            // bucket, otherwise that vertical-slab face silently disappears (the "invisible
            // texture" bug).
            for (BakedQuad quad : this.parent.getQuads(null)) {
                BakedQuad transformed = this.model.transformQuad(quad);
                if (deriveDirection(transformed.position0(), transformed.position1(),
                        transformed.position2()) == direction) {
                    out.add(transformed);
                }
            }
            if (LOG.isDebugEnabled()) {
                long now = System.currentTimeMillis();
                if (now - this.lastQuadLog > 1000L) {
                    this.lastQuadLog = now;
                    LOG.debug("Vertical slab part: cullFace={} produced {} quads (occupied={})",
                            direction, out.size(), this.model.occupied);
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
