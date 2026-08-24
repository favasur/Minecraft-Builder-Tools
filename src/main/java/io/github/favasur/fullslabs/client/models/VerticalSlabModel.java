package io.github.favasur.fullslabs.client.models;

import com.google.common.collect.ImmutableList;
import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.config.Config;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

/**
 * Bakes the vertical-slab model for a given state: the parent slab's textures (particle, side,
 * top, bottom, extracted from its baked model) are injected into a static template geometry
 * (block/vertical/...) and re-baked with a remapping sprite getter.
 * Ported from FullSlabs (Micalobia) 1.21.9's BlockStateModel-based code to the 1.21.1 model API.
 */
@MethodsReturnNonnullByDefault
public final class VerticalSlabModel {
    private static final ResourceLocation ATLAS = TextureAtlas.LOCATION_BLOCKS;
    public static final List<ResourceLocation> TEMPLATES = templates();

    private VerticalSlabModel() {
    }

    private static VerticalSlabBlock verifyVertical(Block block) {
        if (!(block instanceof VerticalSlabBlock slab)) {
            throw new IllegalArgumentException();
        }
        return slab;
    }

    public static ResourceLocation templateId(BlockState state) {
        VerticalSlabBlock slab = verifyVertical(state.getBlock());
        Direction facing = state.getValue(VerticalSlabBlock.DIRECTION);
        VerticalSlabBlock.VerticalType type = state.getValue(VerticalSlabBlock.TYPE);
        boolean tilted = Config.isTilted(slab.parent);
        if (tilted) {
            return FullSlabs.id("block/vertical/tilted/%s_%s".formatted(facing.getSerializedName(), type.getSerializedName()));
        }
        if (type == VerticalSlabBlock.VerticalType.FULL) {
            return FullSlabs.id("block/vertical/normal/full");
        }
        return FullSlabs.id("block/vertical/normal/%s".formatted((type == VerticalSlabBlock.VerticalType.AWAY ? facing.getOpposite() : facing).getSerializedName()));
    }

    public static BlockState parentState(BlockState state) {
        VerticalSlabBlock slab = verifyVertical(state.getBlock());
        VerticalSlabBlock.VerticalType type = state.getValue(VerticalSlabBlock.TYPE);
        BlockState parentState = slab.parent.defaultBlockState();
        return switch (type) {
            case AWAY -> parentState.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
            case TOWARDS -> parentState.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
            case FULL -> parentState.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        };
    }

    public static ResourceLocation makeModelId(BlockState state) {
        VerticalSlabBlock slab = verifyVertical(state.getBlock());
        return FullSlabs.id("block/%s/%s_%s".formatted(
                BuiltInRegistries.BLOCK.getKey(slab).getPath(),
                state.getValue(VerticalSlabBlock.DIRECTION).getSerializedName(),
                state.getValue(VerticalSlabBlock.TYPE).getSerializedName()));
    }

    /**
     * Bakes the template geometry for {@code state} with the parent slab's textures.
     *
     * @param parent the parent slab's baked model (source of the textures)
     */
    public static BakedModel bake(BlockState state, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, BakedModel parent) {
        ResourceLocation templateId = templateId(state);
        UnbakedModel template = baker.getModel(templateId);
        BlockState parentState = parentState(state);
        TextureAtlasSprite particle = parent.getParticleIcon();
        Textures textures = Textures.fetch(particle, parent, parentState);
        Function<Material, TextureAtlasSprite> remapped = material -> {
            // The template JSONs reference placeholder textures under block/vertical/slots/
            // (1.21.1 has no texture-slot binding like 1.21.9's BlockStateModel, so the templates
            // were given concrete placeholder paths); swap each placeholder for the parent slab's
            // real sprite, and keep every other material as-is.
            String path = material.texture().getPath();
            return switch (path) {
                case "block/vertical/slots/side" -> textures.side;
                case "block/vertical/slots/top" -> textures.top;
                case "block/vertical/slots/bottom" -> textures.bottom;
                case "block/vertical/slots/particle" -> textures.particle;
                default -> spriteGetter.apply(material);
            };
        };
        return template.bake(baker, remapped, BlockModelRotation.X0_Y0);
    }

    private static List<ResourceLocation> templates() {
        ImmutableList.Builder<ResourceLocation> builder = ImmutableList.builder();
        Direction.Plane.HORIZONTAL.forEach(direction -> {
            String str = direction.getSerializedName();
            builder.add(FullSlabs.id("block/vertical/tilted/%s_towards".formatted(str)));
            builder.add(FullSlabs.id("block/vertical/tilted/%s_away".formatted(str)));
            builder.add(FullSlabs.id("block/vertical/tilted/%s_full".formatted(str)));
            builder.add(FullSlabs.id("block/vertical/normal/%s".formatted(str)));
        });
        builder.add(FullSlabs.id("block/vertical/normal/full"));
        return builder.build();
    }

    public record Textures(TextureAtlasSprite particle, TextureAtlasSprite side, TextureAtlasSprite top, TextureAtlasSprite bottom) {
        public static Textures fetch(TextureAtlasSprite particle, BakedModel parent, BlockState parentState) {
            RandomSource random = RandomSource.create(0L);
            TextureAtlasSprite side = Direction.Plane.HORIZONTAL.stream()
                    .map(dir -> fetchFace(dir, parent, parentState, random))
                    .filter(Optional::isPresent).map(Optional::get).findFirst().orElse(particle);
            TextureAtlasSprite top = fetchFace(Direction.UP, parent, parentState, random).orElse(side);
            TextureAtlasSprite bottom = fetchFace(Direction.DOWN, parent, parentState, random).orElse(side);
            return new Textures(particle, side, top, bottom);
        }

        public static Optional<TextureAtlasSprite> fetchFace(Direction direction, BakedModel parent, BlockState parentState, RandomSource random) {
            List<BakedQuad> quads = parent.getQuads(parentState, direction, random);
            if (!quads.isEmpty()) {
                return Optional.of(quads.getFirst().getSprite());
            }
            return parent.getQuads(parentState, null, random).stream()
                    .filter(q -> q.getDirection() == direction)
                    .map(BakedQuad::getSprite)
                    .findFirst();
        }
    }
}
