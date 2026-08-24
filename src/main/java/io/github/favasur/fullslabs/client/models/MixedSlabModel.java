package io.github.favasur.fullslabs.client.models;

import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.neoforge.FullSlabsNeoForge;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * Baked model for the mixed slab: reads the two halves (towards/away slab states) from the block
 * entity's {@link ModelData} and emits the union of both halves' quads, so the top/bottom (or
 * left/right) faces show two different blocks.
 * Ported from FullSlabs (Micalobia) 1.21.9's BlockStateModel-based code to the 1.21.1 model API.
 */
@MethodsReturnNonnullByDefault
public final class MixedSlabModel implements BakedModel {
    public static final MixedSlabModel INSTANCE = new MixedSlabModel();

    private MixedSlabModel() {
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random) {
        return List.of();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, @Nullable Direction direction, RandomSource random, ModelData modelData, @Nullable RenderType renderType) {
        MixedSlabBlockEntity.ModelContext ctx = modelData.get(FullSlabsNeoForge.MIXED_CONTEXT_MODEL_PROPERTY);
        if (ctx == null) {
            return List.of();
        }
        BlockState towards = ctx.towardsState();
        BlockState away = ctx.awayState();
        Minecraft mc = Minecraft.getInstance();
        BakedModel towardsModel = mc.getBlockRenderer().getBlockModel(towards);
        BakedModel awayModel = mc.getBlockRenderer().getBlockModel(away);
        List<BakedQuad> out = new ArrayList<>();
        out.addAll(towardsModel.getQuads(towards, direction, random, modelData, renderType));
        out.addAll(awayModel.getQuads(away, direction, random, modelData, renderType));
        return out;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return Minecraft.getInstance().getModelManager().getMissingModel().getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
