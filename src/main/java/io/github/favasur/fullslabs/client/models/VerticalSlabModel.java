package io.github.favasur.fullslabs.client.models;

import io.github.favasur.fullslabs.block.SlabVertical;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The model for a vertical slab state (1.21.1). Since a vertical slab is the same {@link
 * net.minecraft.world.level.block.SlabBlock} as its horizontal form, this model delegates to the
 * block's own model for the bottom, non-vertical state and re-emits its quads rotated 90 degrees
 * onto the slab's edge. Because the source model is the block's real baked model, this works for
 * every slab in the game - vanilla or modded - with no resource files and no per-slab registry
 * entries.
 */
public final class VerticalSlabModel implements BakedModel {
    private static final Logger LOG = LogManager.getLogger("FullSlabs");
    /** One-shot diagnostic: dumps the parent model's quad counts per cull face on first render so
     *  we can see where the slab's top-face geometry actually lives (the "west big face missing"
     *  bug traces to the parent yielding no UP culled quads). */
    private static boolean parentBakeLogged;
    /** Vertex stride of the BLOCK vertex format (position, color, uv0, uv2, normal). */
    private static final int VERTEX_STRIDE = 8;

    private final BakedModel parent;
    private final Direction occupied;
    private final Matrix4f transform;
    private final Quaternionf rotation;
    /** Parent cull face -> transformed cull face (forward rotation). */
    private final Map<Direction, Direction> forward;
    /** Requested cull face -> parent cull face (inverse rotation). */
    private final Map<Direction, Direction> inverse;

    public VerticalSlabModel(BakedModel parent, Direction occupied) {
        this.parent = parent;
        this.occupied = occupied;
        this.rotation = new Quaternionf();
        Vector3f translation = new Vector3f();
        switch (occupied) {
            case NORTH -> {
                this.rotation.rotationX(-(float) Math.PI / 2.0F);
                translation.set(0.0F, 0.0F, 8.0F);
            }
            case SOUTH -> {
                this.rotation.rotationX((float) Math.PI / 2.0F);
                translation.set(0.0F, 16.0F, 8.0F);
            }
            case WEST -> {
                this.rotation.rotationZ((float) Math.PI / 2.0F);
                translation.set(8.0F, 0.0F, 0.0F);
            }
            case EAST -> {
                this.rotation.rotationZ(-(float) Math.PI / 2.0F);
                translation.set(8.0F, 16.0F, 0.0F);
            }
            default -> throw new IllegalArgumentException("Invalid vertical direction " + occupied);
        }
        // M = T * R so that p' = R(p) + t
        this.transform = new Matrix4f().translate(translation).rotate(this.rotation);
        this.forward = directionMap(this.rotation);
        this.inverse = directionMap(this.rotation.conjugate(new Quaternionf()));
    }

    private static Map<Direction, Direction> directionMap(Quaternionf rot) {
        Map<Direction, Direction> map = new EnumMap<>(Direction.class);
        for (Direction from : Direction.values()) {
            Vector3f v = new Vector3f(from.step()).rotate(rot);
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
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        Direction parentSide = side == null ? null : this.inverse.get(side);
        List<BakedQuad> out = this.parent.getQuads(SlabVertical.flat(state), parentSide, random)
                .stream().map(this::transformQuad).toList();
        if (LOG.isDebugEnabled()) {
            // "No visible textures" symptom: if a cull face maps to a parent face that yields no
            // quads here, that face is silently missing - log the mapping and quad count.
            LOG.debug("Vertical slab {}: cullFace={} -> parentCull={} produced {} quads",
                    this.occupied, side, parentSide, out.size());
            // One-shot diagnosis of WHERE the parent's geometry lives: dump every cull face's
            // quad count for the flat parent state. This reveals whether the missing big-west
            // face (parent UP, from inverse of WEST) has its quads under null, under a side face,
            // or is genuinely absent.
            if (parentBakeLogged == false) {
                parentBakeLogged = true;
                for (Direction d : Direction.values()) {
                    LOG.debug("Vertical-slab parent bake: flat={} cull={} quads={}",
                            SlabVertical.flat(state), d, this.parent.getQuads(SlabVertical.flat(state), d, random).size());
                }
                LOG.debug("Vertical-slab parent bake: flat={} cull=null quads={}",
                        SlabVertical.flat(state), this.parent.getQuads(SlabVertical.flat(state), null, random).size());
            }
        }
        return out;
    }

    private BakedQuad transformQuad(BakedQuad quad) {
        int[] source = quad.getVertices();
        int[] vertices = source.clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * VERTEX_STRIDE;
            Vector3f position = new Vector3f(
                    Float.intBitsToFloat(source[base]),
                    Float.intBitsToFloat(source[base + 1]),
                    Float.intBitsToFloat(source[base + 2])).mulPosition(this.transform);
            vertices[base] = Float.floatToRawIntBits(position.x());
            vertices[base + 1] = Float.floatToRawIntBits(position.y());
            vertices[base + 2] = Float.floatToRawIntBits(position.z());
            int packedNormal = source[base + 7];
            float nx = (byte) (packedNormal & 0xFF) / 127.0F;
            float ny = (byte) ((packedNormal >> 8) & 0xFF) / 127.0F;
            float nz = (byte) ((packedNormal >> 16) & 0xFF) / 127.0F;
            Vector3f normal = new Vector3f(nx, ny, nz).rotate(this.rotation);
            vertices[base + 7] = packNormal(normal);
        }
        Direction direction = quad.getDirection() == null ? null : this.forward.get(quad.getDirection());
        return new BakedQuad(vertices, quad.getTintIndex(), direction, quad.getSprite(), quad.isShade());
    }

    private static int packNormal(Vector3f normal) {
        int x = (int) Math.round(normal.x() * 127.0F) & 0xFF;
        int y = (int) Math.round(normal.y() * 127.0F) & 0xFF;
        int z = (int) Math.round(normal.z() * 127.0F) & 0xFF;
        return x | y << 8 | z << 16;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return this.parent.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return this.parent.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return this.parent.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return this.parent.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return this.parent.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return this.parent.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return this.parent.getOverrides();
    }
}
