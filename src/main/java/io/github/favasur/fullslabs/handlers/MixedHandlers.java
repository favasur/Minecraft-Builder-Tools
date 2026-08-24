package io.github.favasur.fullslabs.handlers;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.handlers.MixedHandler;
import io.github.favasur.fullslabs.handlers.VanillaMixedHandler;
import io.github.favasur.fullslabs.util.Utility;
import java.util.HashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import org.jetbrains.annotations.Nullable;

public final class MixedHandlers {
    private static final HashMap<ResourceLocation, MixedHandlerFactory> ID_HANDLERS = new HashMap();
    private static final HashMap<SlabBlock, MixedHandlerFactory> BLOCK_HANDLERS = new HashMap();
    private static final HashMap<Class<? extends SlabBlock>, MixedHandlerFactory> CLASS_HANDLERS = new HashMap();
    private static final HashMap<SlabBlock, MixedHandler> HANDLERS = new HashMap();

    public static boolean hasHandler(Block block) {
        if (!Utility.isSlab(block)) {
            return false;
        }
        MixedHandler handler = MixedHandlers.get(block);
        if (handler instanceof VanillaMixedHandler) {
            VanillaMixedHandler vanilla = (VanillaMixedHandler)handler;
            return vanilla.valid;
        }
        return handler != null;
    }

    @Nullable
    public static MixedHandler get(Block block) {
        if (!Utility.isSlab(block)) {
            return null;
        }
        SlabBlock slab = VerticalSlabBlock.getRoot(block);
        MixedHandler handler = HANDLERS.get(slab);
        if (handler != null) {
            return handler;
        }
        MixedHandlers.resolve(block);
        MixedHandlerFactory factory = BLOCK_HANDLERS.get(slab);
        if (factory == null) {
            factory = CLASS_HANDLERS.get(slab.getClass());
        }
        if (factory == null) {
            FullSlabs.LOGGER.warn("{} missing mixed handler; Using default", BuiltInRegistries.BLOCK.getId(block));
            factory = s -> VanillaMixedHandler.INVALID;
        }
        if ((handler = factory.create(slab)) != null) {
            HANDLERS.put(slab, handler);
        }
        return handler;
    }

    public static MixedHandler getOrThrow(Block block) {
        MixedHandler handler = MixedHandlers.get(block);
        if (handler == null) {
            throw new IllegalArgumentException("Missing handler for %s (%s)!".formatted(block, block.getClass().getSimpleName()));
        }
        return handler;
    }

    public static void register(ResourceLocation identifier, MixedHandler handler) {
        MixedHandlers.register(identifier, (SlabBlock slab) -> handler);
    }

    public static void register(ResourceLocation identifier, MixedHandlerFactory factory) {
        BuiltInRegistries.BLOCK.getOptional(identifier).ifPresentOrElse(block -> MixedHandlers.register(block, factory), () -> ID_HANDLERS.put(identifier, factory));
    }

    public static void register(Block block, MixedHandler handler) {
        MixedHandlers.register(block, (SlabBlock slab) -> handler);
    }

    public static void register(Block block, MixedHandlerFactory factory) {
        if (block instanceof SlabBlock) {
            SlabBlock slab = (SlabBlock)block;
            BLOCK_HANDLERS.put(slab, factory);
        } else if (block instanceof VerticalSlabBlock) {
            VerticalSlabBlock slab = (VerticalSlabBlock)block;
            BLOCK_HANDLERS.put(slab.parent, factory);
        } else {
            throw new IllegalArgumentException("Tried to register a handler for a block that wasn't a slab!");
        }
    }

    public static <T extends SlabBlock> void register(Class<T> klass, MixedHandler handler) {
        MixedHandlers.register(klass, (SlabBlock slab) -> handler);
    }

    public static <T extends SlabBlock> void register(Class<T> klass, MixedHandlerFactory factory) {
        CLASS_HANDLERS.put(klass, factory);
    }

    private static void resolve(ResourceLocation id) {
        BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> MixedHandlers.resolve(block, id));
    }

    private static void resolve(Block block) {
        MixedHandlers.resolve(block, BuiltInRegistries.BLOCK.getKey(block));
    }

    private static void resolve(Block block, ResourceLocation id) {
        if (ID_HANDLERS.containsKey(id)) {
            MixedHandlers.register(block, ID_HANDLERS.remove(id));
        }
    }

    public static interface MixedHandlerFactory {
        public MixedHandler create(SlabBlock var1);
    }
}

