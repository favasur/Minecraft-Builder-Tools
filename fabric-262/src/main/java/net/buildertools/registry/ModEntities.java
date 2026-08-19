package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final EntityType<OffGridBlockEntity> OFF_GRID_BLOCK = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(BuilderToolsMod.MODID, "off_grid_block"),
            EntityType.Builder.of(OffGridBlockEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .noSummon()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(BuilderToolsMod.MODID, "off_grid_block"))));

    private ModEntities() {
    }
}
