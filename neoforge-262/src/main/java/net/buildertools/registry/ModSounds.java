package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sound events for the builder tools. The audio files live in assets/buildertools/sounds and
 * are mapped here following the SFX definitions of the original Creative Play sound set.
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, BuilderToolsMod.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> SET_CORNER_1 = register("selection.set_corner1");
    public static final DeferredHolder<SoundEvent, SoundEvent> SET_CORNER_2 = register("selection.set_corner2");
    public static final DeferredHolder<SoundEvent, SoundEvent> CLEAR_SELECTION = register("selection.clear");
    public static final DeferredHolder<SoundEvent, SoundEvent> COPY = register("selection.copy");
    public static final DeferredHolder<SoundEvent, SoundEvent> PASTE = register("selection.paste");
    public static final DeferredHolder<SoundEvent, SoundEvent> FILL = register("selection.fill");
    public static final DeferredHolder<SoundEvent, SoundEvent> UNDO = register("selection.undo");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENTITY_SELECT = register("entity.select");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENTITY_DESELECT = register("entity.deselect");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENTITY_MOVE = register("entity.move");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENTITY_ROTATE = register("entity.rotate");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENTITY_DELETE = register("entity.delete");
    public static final DeferredHolder<SoundEvent, SoundEvent> ENTITY_DUPLICATE = register("entity.duplicate");
    public static final DeferredHolder<SoundEvent, SoundEvent> ERROR = register("error");

    public static final DeferredHolder<SoundEvent, SoundEvent> RULER_POINT_A = register("tool.ruler.point_a");
    public static final DeferredHolder<SoundEvent, SoundEvent> RULER_POINT_B = register("tool.ruler.point_b");
    public static final DeferredHolder<SoundEvent, SoundEvent> RULER_CLEAR = register("tool.ruler.clear");
    public static final DeferredHolder<SoundEvent, SoundEvent> PAINT = register("tool.paint");
    public static final DeferredHolder<SoundEvent, SoundEvent> SCATTER = register("tool.scatter");
    public static final DeferredHolder<SoundEvent, SoundEvent> SMOOTH = register("tool.smooth");

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(BuilderToolsMod.MODID, name)));
    }
}
