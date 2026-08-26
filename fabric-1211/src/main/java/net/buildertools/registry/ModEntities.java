package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, BuilderToolsMod.MODID);

    public static final Supplier<EntityType<OffGridBlockEntity>> OFF_GRID_BLOCK = ENTITIES.register("off_grid_block",
            () -> EntityType.Builder.of(OffGridBlockEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .noSummon()
                    .build("off_grid_block"));

    private ModEntities() {
    }
}
