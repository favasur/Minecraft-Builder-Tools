package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BuilderToolsMod.MODID);

    public static final RegistryObject<EntityType<OffGridBlockEntity>> OFF_GRID_BLOCK = ENTITIES.register("off_grid_block",
            () -> EntityType.Builder.of(OffGridBlockEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .noSummon()
                    .build("off_grid_block"));

    private ModEntities() {
    }
}
