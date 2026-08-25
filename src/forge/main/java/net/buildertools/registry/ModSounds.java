package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Sound events for the builder tools. The audio files live in assets/buildertools/sounds and
 * are mapped here following the SFX definitions of the original Creative Play sound set.
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, BuilderToolsMod.MODID);

    public static final RegistryObject<SoundEvent> SET_CORNER_1 = register("selection.set_corner1");
    public static final RegistryObject<SoundEvent> SET_CORNER_2 = register("selection.set_corner2");
    public static final RegistryObject<SoundEvent> CLEAR_SELECTION = register("selection.clear");
    public static final RegistryObject<SoundEvent> COPY = register("selection.copy");
    public static final RegistryObject<SoundEvent> PASTE = register("selection.paste");
    public static final RegistryObject<SoundEvent> FILL = register("selection.fill");
    public static final RegistryObject<SoundEvent> UNDO = register("selection.undo");
    public static final RegistryObject<SoundEvent> ENTITY_SELECT = register("entity.select");
    public static final RegistryObject<SoundEvent> ENTITY_DESELECT = register("entity.deselect");
    public static final RegistryObject<SoundEvent> ENTITY_MOVE = register("entity.move");
    public static final RegistryObject<SoundEvent> ENTITY_ROTATE = register("entity.rotate");
    public static final RegistryObject<SoundEvent> ENTITY_DELETE = register("entity.delete");
    public static final RegistryObject<SoundEvent> ENTITY_DUPLICATE = register("entity.duplicate");
    public static final RegistryObject<SoundEvent> ERROR = register("error");

    public static final RegistryObject<SoundEvent> RULER_POINT_A = register("tool.ruler.point_a");
    public static final RegistryObject<SoundEvent> RULER_POINT_B = register("tool.ruler.point_b");
    public static final RegistryObject<SoundEvent> RULER_CLEAR = register("tool.ruler.clear");
    public static final RegistryObject<SoundEvent> PAINT = register("tool.paint");
    public static final RegistryObject<SoundEvent> SCATTER = register("tool.scatter");
    public static final RegistryObject<SoundEvent> SMOOTH = register("tool.smooth");

    private ModSounds() {
    }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(BuilderToolsMod.MODID, name)));
    }
}
