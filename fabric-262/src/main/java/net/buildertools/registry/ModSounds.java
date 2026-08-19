package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Sound events for the builder tools. The audio files live in assets/buildertools/sounds.
 */
public final class ModSounds {
    public static final SoundEvent SET_CORNER_1 = register("selection.set_corner1");
    public static final SoundEvent SET_CORNER_2 = register("selection.set_corner2");
    public static final SoundEvent CLEAR_SELECTION = register("selection.clear");
    public static final SoundEvent COPY = register("selection.copy");
    public static final SoundEvent PASTE = register("selection.paste");
    public static final SoundEvent FILL = register("selection.fill");
    public static final SoundEvent UNDO = register("selection.undo");
    public static final SoundEvent ENTITY_SELECT = register("entity.select");
    public static final SoundEvent ENTITY_DESELECT = register("entity.deselect");
    public static final SoundEvent ENTITY_MOVE = register("entity.move");
    public static final SoundEvent ENTITY_ROTATE = register("entity.rotate");
    public static final SoundEvent ENTITY_DELETE = register("entity.delete");
    public static final SoundEvent ENTITY_DUPLICATE = register("entity.duplicate");
    public static final SoundEvent ERROR = register("error");

    public static final SoundEvent RULER_POINT_A = register("tool.ruler.point_a");
    public static final SoundEvent RULER_POINT_B = register("tool.ruler.point_b");
    public static final SoundEvent RULER_CLEAR = register("tool.ruler.clear");
    public static final SoundEvent PAINT = register("tool.paint");
    public static final SoundEvent SCATTER = register("tool.scatter");
    public static final SoundEvent SMOOTH = register("tool.smooth");

    private ModSounds() {
    }

    private static SoundEvent register(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(BuilderToolsMod.MODID, name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }
}
