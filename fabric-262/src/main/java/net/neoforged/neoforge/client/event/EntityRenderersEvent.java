package net.neoforged.neoforge.client.event;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public final class EntityRenderersEvent {
    private EntityRenderersEvent() {
    }

    /**
     * Fired on the mod bus once, client-side, with a registrar backed by Fabric's
     * {@code EntityRendererRegistry} so the canonical {@code registerEntityRenderer} calls
     * actually take effect.
     */
    public static final class RegisterRenderers {
        @FunctionalInterface
        public interface Registrar {
            void register(EntityType<?> type, EntityRendererProvider<?> provider);
        }

        private final Registrar registrar;

        public RegisterRenderers() {
            this((type, provider) -> {
            });
        }

        public RegisterRenderers(Registrar registrar) {
            this.registrar = registrar;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Entity> void registerEntityRenderer(EntityType<T> type, EntityRendererProvider<T> provider) {
            registrar.register(type, provider);
        }
    }
}
