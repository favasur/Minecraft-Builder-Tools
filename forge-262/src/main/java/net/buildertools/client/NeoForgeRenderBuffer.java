package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.ARGB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small compatibility buffer for the copied in-world tool overlays. Minecraft 26.2 removed
 * MultiBufferSource, so vertices are recorded at call time and submitted as feature geometry at the
 * end of the current level submission event.
 */
public final class NeoForgeRenderBuffer {
    private final Map<RenderType, RecordingVertexConsumer> buffers = new LinkedHashMap<>();

    public VertexConsumer getBuffer(RenderType renderType) {
        return buffers.computeIfAbsent(renderType, ignored -> new RecordingVertexConsumer());
    }

    /**
     * Emits a baked quad (block geometry) into the recording buffer. The render type is taken
     * from the quad's own material info so translucent block layers keep their sheet.
     */
    public void putBakedQuad(PoseStack poseStack, BakedQuad quad, QuadInstance instance) {
        RenderType renderType = quad.materialInfo().itemRenderType();
        if (renderType == null) {
            renderType = Sheets.cutoutBlockItemSheet();
        }
        VertexConsumer consumer = getBuffer(renderType);
        org.joml.Vector3f normal = quadNormal(quad.position(0), quad.position(1), quad.position(2), quad.position(3));
        org.joml.Vector3f worldNormal = poseStack.last().transformNormal(normal, new org.joml.Vector3f());
        for (int i = 0; i < 4; i++) {
            long packedUv = quad.packedUV(i);
            int light = instance.getLightCoordsWithEmission(i, quad.materialInfo().lightEmission());
            // The per-vertex color already carries the block tint and face shade (set by
            // RotatedBlockRendering into the QuadInstance); Forge's BakedQuad carries no baked colors.
            int vertexColor = instance.getColor(i);
            consumer.addVertex(poseStack.last(), quad.position(i).x(), quad.position(i).y(), quad.position(i).z())
                    .setColor(vertexColor)
                    .setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv))
                    .setUv1(instance.overlayCoords(), instance.overlayCoords())
                    .setUv2(light & 0xFFFF, (light >>> 16) & 0xFFFF)
                    .setNormal(worldNormal.x, worldNormal.y, worldNormal.z);
        }
    }

    /**
     * Computes the (world-space) face normal of a quad from its four corners, matching the
     * vanilla packed-int encoding NeoForge's BakedNormals helper produces.
     */
    private static org.joml.Vector3f quadNormal(org.joml.Vector3fc p0, org.joml.Vector3fc p1,
                                                org.joml.Vector3fc p2, org.joml.Vector3fc p3) {
        org.joml.Vector3f a = new org.joml.Vector3f(p1).sub(p0);
        org.joml.Vector3f b = new org.joml.Vector3f(p3).sub(p0);
        org.joml.Vector3f normal = new org.joml.Vector3f();
        a.cross(b, normal);
        normal.normalize();
        return normal;
    }

    /**
     * Submits every recorded render type through the event's collector. Vertices were recorded
     * through the camera-space pose (world minus camera), so the event's own pose stack - which
     * carries the same space - is reused as the draw transform.
     */
    public void submit(ForgeLevelRenderEvent event) {
        for (Map.Entry<RenderType, RecordingVertexConsumer> entry : buffers.entrySet()) {
            List<Vertex> vertices = entry.getValue().snapshot();
            if (vertices.isEmpty()) {
                continue;
            }
            event.getSubmitNodeCollector().submitCustomGeometry(
                    event.getPoseStack(),
                    entry.getKey(),
                    (pose, builder) -> {
                        for (Vertex vertex : vertices) {
                            builder.addVertex(vertex.x, vertex.y, vertex.z)
                                    .setColor(vertex.color)
                                    .setUv(vertex.u, vertex.v)
                                    .setUv1(vertex.overlayU, vertex.overlayV)
                                    .setUv2(vertex.lightU, vertex.lightV)
                                    .setNormal(vertex.normalX, vertex.normalY, vertex.normalZ)
                                    .setLineWidth(vertex.lineWidth);
                        }
                    }
            );
        }
        buffers.clear();
    }

    /** Shared frame buffer for legacy entity and in-world overlay geometry. */
    private static final NeoForgeRenderBuffer SHARED = new NeoForgeRenderBuffer();

    public static NeoForgeRenderBuffer shared() {
        return SHARED;
    }

    public static void renderFilledBox(PoseStack poseStack, NeoForgeRenderBuffer buffers,
                                       AABB box, float red, float green, float blue, float alpha) {
        int color = ((int) (alpha * 255.0F) & 0xFF) << 24
                | ((int) (red * 255.0F) & 0xFF) << 16
                | ((int) (green * 255.0F) & 0xFF) << 8
                | ((int) (blue * 255.0F) & 0xFF);
        VertexConsumer consumer = buffers.getBuffer(RenderTypes.debugFilledBox());
        PoseStack.Pose pose = poseStack.last();
        quad(consumer, pose, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ,
                box.maxX, box.maxY, box.minZ, box.minX, box.maxY, box.minZ, color);
        quad(consumer, pose, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ,
                box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, color);
        quad(consumer, pose, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ,
                box.minX, box.maxY, box.minZ, box.minX, box.maxY, box.maxZ, color);
        quad(consumer, pose, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ,
                box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ, color);
        quad(consumer, pose, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ,
                box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
        quad(consumer, pose, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ,
                box.maxX, box.minY, box.minZ, box.minX, box.minY, box.minZ, color);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3, int color) {
        consumer.addVertex(pose, (float) x0, (float) y0, (float) z0).setColor(color);
        consumer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color);
        consumer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color);
        consumer.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(color);
    }

    public static void renderLineBox(PoseStack poseStack, NeoForgeRenderBuffer buffers, AABB box, int color) {
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        line(poseStack, lines, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, color);
        line(poseStack, lines, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, color);
        line(poseStack, lines, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, color);
        line(poseStack, lines, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ, color);
        line(poseStack, lines, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, color);
        line(poseStack, lines, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, color);
        line(poseStack, lines, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
        line(poseStack, lines, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, color);
        line(poseStack, lines, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, color);
        line(poseStack, lines, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, color);
        line(poseStack, lines, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, color);
        line(poseStack, lines, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
    }

    private static void line(PoseStack poseStack, VertexConsumer consumer,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1, int color) {
        PoseStack.Pose pose = poseStack.last();
        consumer.addVertex(pose, (float) x0, (float) y0, (float) z0).setColor(color);
        consumer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color);
    }

    private static final class RecordingVertexConsumer implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private Vertex current;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.current = new Vertex(x, y, z);
            this.vertices.add(this.current);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this.setColor((alpha & 0xFF) << 24 | (red & 0xFF) << 16
                    | (green & 0xFF) << 8 | (blue & 0xFF));
        }

        @Override
        public VertexConsumer setColor(int color) {
            if (current != null) {
                current.color = color;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (current != null) {
                current.u = u;
                current.v = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            if (current != null) {
                current.overlayU = u;
                current.overlayV = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (current != null) {
                current.lightU = u;
                current.lightV = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (current != null) {
                current.normalX = x;
                current.normalY = y;
                current.normalZ = z;
            }
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            if (current != null) {
                current.lineWidth = width;
            }
            return this;
        }

        private List<Vertex> snapshot() {
            return List.copyOf(vertices);
        }
    }

    private static final class Vertex {
        private final float x;
        private final float y;
        private final float z;
        private int color = -1;
        private float u;
        private float v;
        private int overlayU;
        private int overlayV;
        private int lightU;
        private int lightV;
        private float normalX;
        private float normalY = 1.0F;
        private float normalZ;
        private float lineWidth = 1.0F;

        private Vertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
