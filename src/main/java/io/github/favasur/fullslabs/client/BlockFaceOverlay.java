package io.github.favasur.fullslabs.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.favasur.fullslabs.block.SlabVertical;
import io.github.favasur.fullslabs.config.Config;
import io.github.favasur.fullslabs.config.Controls;
import io.github.favasur.fullslabs.util.RotatedSlabPlacement;
import io.github.favasur.fullslabs.util.SlabPlacement;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class BlockFaceOverlay {
    private static final double EPSILON = 1.0E-4;

    private BlockFaceOverlay() {
    }

    /**
     * The overlay renders through the debug quad/line-strip render types, which were removed in
     * later 1.21.x minors. They are created lazily behind a version probe so the class still loads
     * on versions without them (the overlay is then simply skipped).
     */
    private static final class Layers {
        static final RenderType QUAD_LAYER = RenderType.debugQuads();
        static final RenderType LINE_LAYER = RenderType.debugLineStrip(2.0);
    }

    public static void renderFaceOverlay(Camera camera) {
        if (!net.buildertools.util.ApiCompat.debugQuads()) {
            return;
        }
        if (!Controls.isOverlayActive()) {
            return;
        }
        Config.load();
        Minecraft mc = Minecraft.getInstance();
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult)) {
            return;
        }
        BlockHitResult bhr = (BlockHitResult)hitResult;
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (!player.isHolding(stack -> stack.getItem() instanceof net.minecraft.world.item.BlockItem item
                && item.getBlock() instanceof SlabBlock)) {
            return;
        }
        ClientLevel world = mc.level;
        if (world == null) {
            return;
        }
        BlockPos pos = bhr.getBlockPos();
        Direction face = bhr.getDirection();
        Vec3 hit = bhr.getLocation();
        BlockState state = world.getBlockState(pos);
        // A Builder Tools rotated block in the cell: the region highlight must follow the block's
        // ACTUAL rotated face (the axis-aligned cell face no longer matches its geometry).
        RotatedBlockLookup lookup = RotatedBlockLookup.get();
        RotatedBlockLookup.Target rotated = lookup != null ? lookup.at(world, pos) : null;
        if (rotated != null) {
            BlockFaceOverlay.renderRotatedFaceOverlay(player, camera.getPosition(), rotated, hit);
            return;
        }
        BlockFaceOverlay.renderFaceOverlay(player, camera.getPosition(), world, pos, state, face, hit);
    }

    /**
     * Region highlight on the actual rotated face of a Builder Tools rotated block under the
     * cursor. The click is resolved in the block's LOCAL frame (same math the placement path
     * uses), the region polygon is clipped in local UV space, and the whole face is drawn through
     * the block's world rotation so the highlight lies exactly on the visible face.
     */
    private static void renderRotatedFaceOverlay(Player player, Vec3 camera, RotatedBlockLookup.Target rotated, Vec3 hit) {
        SlabPlacement.Mode mode = Controls.getPlacementMode(player.getUUID());
        Quaternionf rotation = RotatedSlabPlacement.rotation(rotated.yaw(), rotated.pitch());
        RotatedSlabPlacement.LocalClick click = RotatedSlabPlacement.localClick(
                rotation, rotated.shapeBounds(), rotated.center(), hit, player.getDirection());
        FaceRegion region = BlockFaceOverlay.getRegion(
                mode, click.face(), click.localFacing(), BlockPos.ZERO, click.localHit());
        // Same-material merge (mirrors the vanilla vertical-slab rule): clicking the inner face
        // of a rotated vertical slab fills the block - highlight the whole face instead of a
        // single region.
        if (player.getMainHandItem().getItem() instanceof BlockItem held
                && held.getBlock() == rotated.state().getBlock()
                && SlabVertical.isVertical(rotated.state())
                && SlabVertical.isInsideSlab(rotated.state(), BlockPos.ZERO, click.localHit())) {
            region = null;
        }
        // The world frame of the clicked local face.
        FaceFrame local = FaceFrame.create(click.face());
        Vec3 n = BlockFaceOverlay.rotate(local.n(), rotation);
        // The slab lands half a block off the face plane; the pose origin sits one unit behind
        // the landing-box center so the frame's +N/2 bias lands exactly on the face plane.
        RotatedSlabPlacement.Result result = RotatedSlabPlacement.place(
                mode, rotation, rotated.shapeBounds(), rotated.center(), hit, player.getDirection());
        Vec3 origin = result.boxCenter().subtract(n.x, n.y, n.z);
        RectUV rect = BlockFaceOverlay.faceRectOnBox(click.face(),
                rotated.shapeBounds().minX, rotated.shapeBounds().minY, rotated.shapeBounds().minZ,
                rotated.shapeBounds().maxX, rotated.shapeBounds().maxY, rotated.shapeBounds().maxZ,
                click.localHit().x, click.localHit().y, click.localHit().z);
        if (rect == null || rect.isDegenerate()) {
            return;
        }
        List<Vec2> poly = BlockFaceOverlay.rectToCenteredPolygon(rect);
        poly = BlockFaceOverlay.clipPolygonByAt(mode, poly, click.face(), region);
        if (poly.size() < 3) {
            return;
        }
        FaceFrame world = new FaceFrame(click.face(),
                BlockFaceOverlay.rotate(local.u(), rotation),
                BlockFaceOverlay.rotate(local.v(), rotation), n);
        LinkedHashMap<RenderType, ByteBufferBuilder> map = new LinkedHashMap<RenderType, ByteBufferBuilder>();
        map.put(Layers.QUAD_LAYER, new ByteBufferBuilder(1024));
        map.put(Layers.LINE_LAYER, new ByteBufferBuilder(512));
        MultiBufferSource.BufferSource immediate = MultiBufferSource.immediateWithBuffers(map, new ByteBufferBuilder(1024));
        PoseStack stack = new PoseStack();
        stack.pushPose();
        stack.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
        PoseStack.Pose entry = stack.last();
        BlockFaceOverlay.emitFill(entry, immediate, world, poly);
        immediate.endBatch();
        HashMap<EdgeKey, UVSeg> edgeMap = new HashMap<EdgeKey, UVSeg>();
        for (int i = 0; i < poly.size(); ++i) {
            Vec2 a = poly.get(i);
            Vec2 b = poly.get((i + 1) % poly.size());
            BlockFaceOverlay.addEdgeQuantized(edgeMap, a, b);
        }
        BlockFaceOverlay.drawLines(entry, immediate, world, edgeMap);
        stack.popPose();
    }

    /** Rotates a world-space axis vector by the block rotation. */
    private static Vec3 rotate(Vec3 v, Quaternionf rotation) {
        Vector3f out = rotation.transform(new Vector3f((float) v.x, (float) v.y, (float) v.z), new Vector3f());
        return new Vec3(out.x, out.y, out.z);
    }

    private static void renderFaceOverlay(Player player, Vec3 camera, BlockAndTintGetter world, BlockPos pos, BlockState state, Direction face, Vec3 hit) {
        FaceFrame frame = FaceFrame.create(face);
        Direction playerFacing = player.getDirection();
        SlabPlacement.Mode mode = Controls.getPlacementMode(player.getUUID());
        FaceRegion region = BlockFaceOverlay.getRegion(mode, face, playerFacing, pos, hit);
        // The inner (cut) face of a vertical slab fills to FULL on its centre, but in hybrid mode
        // its edge regions also place adjacent slabs, so draw those regions there as well.
        final FaceRegion at;
        if (state.getBlock() instanceof SlabBlock && SlabVertical.isInsideSlab(state, pos, hit)
                && (!SlabVertical.isVertical(state) || mode != SlabPlacement.Mode.HYBRID)) {
            at = null;
        } else {
            at = region;
        }
        VoxelShape outline = state.getShape((BlockGetter)world, pos, CollisionContext.of((Entity)player));
        if (outline.isEmpty()) {
            return;
        }
        Vec3 nHit = hit.subtract((double)pos.getX(), (double)pos.getY(), (double)pos.getZ());
        LinkedHashMap<RenderType, ByteBufferBuilder> map = new LinkedHashMap<RenderType, ByteBufferBuilder>();
        map.put(Layers.QUAD_LAYER, new ByteBufferBuilder(1024));
        map.put(Layers.LINE_LAYER, new ByteBufferBuilder(512));
        MultiBufferSource.BufferSource immediate = MultiBufferSource.immediateWithBuffers(map, (ByteBufferBuilder)new ByteBufferBuilder(1024));
        PoseStack stack = new PoseStack();
        stack.pushPose();
        stack.translate((double)pos.getX() + 0.5 - camera.x, (double)pos.getY() + 0.5 - camera.y, (double)pos.getZ() + 0.5 - camera.z);
        switch (face) {
            case DOWN: {
                stack.translate(0.0, nHit.y, 0.0);
                break;
            }
            case UP: {
                stack.translate(0.0, nHit.y - 1.0, 0.0);
                break;
            }
            case NORTH: {
                stack.translate(0.0, 0.0, nHit.z);
                break;
            }
            case SOUTH: {
                stack.translate(0.0, 0.0, nHit.z - 1.0);
                break;
            }
            case WEST: {
                stack.translate(nHit.x, 0.0, 0.0);
                break;
            }
            case EAST: {
                stack.translate(nHit.x - 1.0, 0.0, 0.0);
            }
        }
        PoseStack.Pose entry = stack.last();
        HashMap<EdgeKey, UVSeg> edgeMap = new HashMap<EdgeKey, UVSeg>();
        outline.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            RectUV rect = BlockFaceOverlay.faceRectOnBox(face, minX, minY, minZ, maxX, maxY, maxZ, nHit.x, nHit.y, nHit.z);
            if (rect == null || rect.isDegenerate()) {
                return;
            }
            List<Vec2> poly = BlockFaceOverlay.rectToCenteredPolygon(rect);
            poly = BlockFaceOverlay.clipPolygonByAt(mode, poly, face, at);
            int size = poly.size();
            for (int i = 0; i < size; ++i) {
                Vec2 a = poly.get(i);
                Vec2 b = poly.get((i + 1) % size);
                BlockFaceOverlay.addEdgeQuantized(edgeMap, a, b);
            }
            BlockFaceOverlay.emitFill(entry, (MultiBufferSource)immediate, frame, poly);
        });
        immediate.endBatch();
        BlockFaceOverlay.drawLines(entry, immediate, frame, edgeMap);
        stack.popPose();
    }

    private static void putVertex(VertexConsumer vc, PoseStack.Pose e, Vec3 p, Vec3 n) {
        vc.addVertex(e.pose(), (float)p.x, (float)p.y, (float)p.z).setColor(Config.fillColor).setNormal(e, (float)n.x, (float)n.y, (float)n.z);
    }

    private static Vec3 uvToWorld(double u, double v, FaceFrame b) {
        double half = 0.5;
        double px = u - half;
        double py = v - half;
        Vec3 U = b.u();
        Vec3 V = b.v();
        Vec3 N = b.n();
        double x = U.x() * px + V.x() * py + N.x() * half;
        double y = U.y() * px + V.y() * py + N.y() * half;
        double z = U.z() * px + V.z() * py + N.z() * half;
        return new Vec3(x, y, z);
    }

    private static RectUV faceRectOnBox(Direction face, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double depthX, double depthY, double depthZ) {
        double eps = 1.0E-4;
        return switch (face) {
            default -> throw new MatchException(null, null);
            case Direction.NORTH -> {
                if (Math.abs(minZ - depthZ) < 1.0E-4) {
                    yield new RectUV(minX, minY, maxX, maxY);
                }
                yield null;
            }
            case Direction.SOUTH -> {
                if (Math.abs(maxZ - depthZ) < 1.0E-4) {
                    yield new RectUV(minX, minY, maxX, maxY);
                }
                yield null;
            }
            case Direction.WEST -> {
                if (Math.abs(minX - depthX) < 1.0E-4) {
                    yield new RectUV(minZ, minY, maxZ, maxY);
                }
                yield null;
            }
            case Direction.EAST -> {
                if (Math.abs(maxX - depthX) < 1.0E-4) {
                    yield new RectUV(minZ, minY, maxZ, maxY);
                }
                yield null;
            }
            case Direction.DOWN -> {
                if (Math.abs(minY - depthY) < 1.0E-4) {
                    yield new RectUV(minX, minZ, maxX, maxZ);
                }
                yield null;
            }
            case Direction.UP -> Math.abs(maxY - depthY) < 1.0E-4 ? new RectUV(minX, minZ, maxX, maxZ) : null;
        };
    }

    private static List<Vec2> rectToCenteredPolygon(RectUV r) {
        float u0 = (float)(r.u0 - 0.5);
        float v0 = (float)(r.v0 - 0.5);
        float u1 = (float)(r.u1 - 0.5);
        float v1 = (float)(r.v1 - 0.5);
        ArrayList<Vec2> poly = new ArrayList<Vec2>(4);
        poly.add(new Vec2(u0, v0));
        poly.add(new Vec2(u1, v0));
        poly.add(new Vec2(u1, v1));
        poly.add(new Vec2(u0, v1));
        return poly;
    }

    private static List<Vec2> clipHalfPlane(List<Vec2> in, float a, float b, float d) {
        if (in.isEmpty()) {
            return in;
        }
        ArrayList<Vec2> out = new ArrayList<Vec2>(in.size() + 4);
        Vec2 prev = in.getLast();
        float prevL = a * prev.x + b * prev.y + d;
        boolean prevIn = prevL <= 1.0E-6f;
        for (Vec2 curr : in) {
            float denom;
            boolean currIn;
            float currL = a * curr.x + b * curr.y + d;
            boolean bl = currIn = currL <= 1.0E-6f;
            if (currIn != prevIn && Math.abs(denom = a * (curr.x - prev.x) + b * (curr.y - prev.y)) > 1.0E-7f) {
                float t = -prevL / denom;
                float ix = prev.x + t * (curr.x - prev.x);
                float iy = prev.y + t * (curr.y - prev.y);
                out.add(new Vec2(ix, iy));
            }
            if (currIn) {
                out.add(curr);
            }
            prev = curr;
            prevL = currL;
            prevIn = currIn;
        }
        return out;
    }

    private static List<Vec2> clipPolygonByAt(SlabPlacement.Mode mode, List<Vec2> poly, Direction face, @Nullable FaceRegion at) {
        boolean horizontal = face.getAxis().isVertical();
        return switch (mode) {
            case HYBRID -> {
                if (at != null) {
                    if (horizontal) {
                        // On horizontal faces the near/far strip spans the full width (matching
                        // the placement rule: the part nearest the player hugs the near edge).
                        poly = switch (at) {
                            case CENTER -> strip(poly, -0.25f, 0.25f, -0.25f, 0.25f);
                            case TOP -> clipHalfPlane(poly, 0.0f, -1.0f, 0.25f);
                            case BOTTOM -> clipHalfPlane(poly, 0.0f, 1.0f, 0.25f);
                            case LEFT -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, 1.0f, 0.0f, 0.25f), 0.0f, -1.0f, -0.25f), 0.0f, 1.0f, -0.25f);
                            case RIGHT -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, -1.0f, 0.0f, 0.25f), 0.0f, -1.0f, -0.25f), 0.0f, 1.0f, -0.25f);
                        };
                    } else {
                        poly = switch (at) {
                            case CENTER -> strip(poly, -0.25f, 0.25f, -0.25f, 0.25f);
                            case LEFT -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, 1.0f, 0.0f, 0.25f), 1.0f, -1.0f, 0.0f), 1.0f, 1.0f, 0.0f);
                            case RIGHT -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, -1.0f, 0.0f, 0.25f), -1.0f, 1.0f, 0.0f), -1.0f, -1.0f, 0.0f);
                            case TOP -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, 0.0f, -1.0f, 0.25f), 1.0f, -1.0f, 0.0f), -1.0f, -1.0f, 0.0f);
                            case BOTTOM -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, 0.0f, 1.0f, 0.25f), -1.0f, 1.0f, 0.0f), 1.0f, 1.0f, 0.0f);
                        };
                    }
                }
                yield poly;
            }
            case VANILLA -> {
                if (at != null) {
                    poly = switch (at) {
                        case TOP -> clipHalfPlane(poly, 0.0f, -1.0f, 0.0f);
                        case BOTTOM -> clipHalfPlane(poly, 0.0f, 1.0f, 0.0f);
                        case CENTER, LEFT, RIGHT -> poly;
                    };
                }
                yield poly;
            }
            case VERTICAL -> {
                if (at != null) {
                    if (horizontal) {
                        poly = switch (at) {
                            case CENTER -> strip(poly, -0.25f, 0.25f, -0.25f, 0.25f);
                            case TOP -> clipHalfPlane(poly, 0.0f, -1.0f, 0.25f);
                            case BOTTOM -> clipHalfPlane(poly, 0.0f, 1.0f, 0.25f);
                            case LEFT -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, 1.0f, 0.0f, 0.25f), 0.0f, -1.0f, -0.25f), 0.0f, 1.0f, -0.25f);
                            case RIGHT -> clipHalfPlane(clipHalfPlane(clipHalfPlane(poly, -1.0f, 0.0f, 0.25f), 0.0f, -1.0f, -0.25f), 0.0f, 1.0f, -0.25f);
                        };
                    } else {
                        poly = switch (at) {
                            case TOP -> clipHalfPlane(poly, 0.0f, -1.0f, 0.0f);
                            case BOTTOM -> clipHalfPlane(poly, 0.0f, 1.0f, 0.0f);
                            case LEFT -> clipHalfPlane(poly, 1.0f, 0.0f, 0.0f);
                            case RIGHT -> clipHalfPlane(poly, -1.0f, 0.0f, 0.0f);
                            case CENTER -> poly;
                        };
                    }
                }
                yield poly;
            }
        };
    }

    /** Clips the polygon to the rectangle [u0,u1]x[v0,v1] (centered coordinates). */
    private static List<Vec2> strip(List<Vec2> poly, float u0, float u1, float v0, float v1) {
        return clipHalfPlane(clipHalfPlane(clipHalfPlane(clipHalfPlane(
                poly,
                1.0f, 0.0f, -u0), -1.0f, 0.0f, u1),
                0.0f, 1.0f, -v0), 0.0f, -1.0f, v1);
    }

    private static void emitFill(PoseStack.Pose entry, MultiBufferSource provider, FaceFrame frame, List<Vec2> centeredPoly) {
        if (centeredPoly.size() < 3) {
            return;
        }
        Vec3 n = frame.n();
        int size = centeredPoly.size();
        VertexConsumer vc = provider.getBuffer(Layers.QUAD_LAYER);
        Vec3 p0 = BlockFaceOverlay.uvToWorld((double)centeredPoly.getFirst().x + 0.5, (double)centeredPoly.getFirst().y + 0.5, frame).add(n.x() * 1.0E-4, n.y() * 1.0E-4, n.z() * 1.0E-4);
        for (int i = 1; i < size - 1; ++i) {
            Vec3 p1 = BlockFaceOverlay.uvToWorld((double)centeredPoly.get((int)i).x + 0.5, (double)centeredPoly.get((int)i).y + 0.5, frame).add(n.x() * 1.0E-4, n.y() * 1.0E-4, n.z() * 1.0E-4);
            Vec3 p2 = BlockFaceOverlay.uvToWorld((double)centeredPoly.get((int)(i + 1)).x + 0.5, (double)centeredPoly.get((int)(i + 1)).y + 0.5, frame).add(n.x() * 1.0E-4, n.y() * 1.0E-4, n.z() * 1.0E-4);
            BlockFaceOverlay.putVertex(vc, entry, p0, n);
            BlockFaceOverlay.putVertex(vc, entry, p1, n);
            BlockFaceOverlay.putVertex(vc, entry, p2, n);
            BlockFaceOverlay.putVertex(vc, entry, p2, n);
        }
    }

    private static void drawLines(PoseStack.Pose entry, MultiBufferSource.BufferSource provider, FaceFrame frame, Map<EdgeKey, UVSeg> edgeMap) {
        Vec3 n = frame.n();
        Vec3 offset = n.scale(1.0E-4);
        List<List<Vec2>> chains = BlockFaceOverlay.buildChains(edgeMap);
        for (List<Vec2> chain : chains) {
            chain = BlockFaceOverlay.mergeColinear(chain);
            VertexConsumer vc = provider.getBuffer(Layers.LINE_LAYER);
            for (Vec2 v : chain) {
                Vec3 p = BlockFaceOverlay.uvToWorld((double)v.x + 0.5, (double)v.y + 0.5, frame).add(offset);
                vc.addVertex(entry, (float)p.x, (float)p.y, (float)p.z).setColor(Config.edgeColor).setNormal(entry, (float)n.x, (float)n.y, (float)n.z);
            }
            provider.endBatch();
        }
    }

    private static List<Vec2> mergeColinear(List<Vec2> in) {
        if (in.size() < 2) {
            return in;
        }
        ArrayList<Vec2> out = new ArrayList<Vec2>(in.size());
        Vec2 prev = in.getFirst();
        out.add(prev);
        int pdx = Integer.MIN_VALUE;
        int pdy = Integer.MIN_VALUE;
        for (int i = 1; i < in.size(); ++i) {
            Vec2 curr = in.get(i);
            int dx = Integer.signum(Math.round((curr.x - prev.x) * 1024.0f));
            int dy = Integer.signum(Math.round((curr.y - prev.y) * 1024.0f));
            if (dx == 0 && dy == 0) continue;
            if (pdx == Integer.MIN_VALUE) {
                pdx = dx;
                pdy = dy;
                out.add(curr);
            } else if (dx == pdx && dy == pdy) {
                out.set(out.size() - 1, curr);
            } else {
                pdx = dx;
                pdy = dy;
                out.add(curr);
            }
            prev = curr;
        }
        return out;
    }

    private static FaceRegion getRegion(SlabPlacement.Mode mode, Direction face, Direction playerFacing, BlockPos pos, Vec3 hit) {
        Direction targeted = SlabPlacement.getTargetedDirection(mode, face, playerFacing, pos, hit);
        if (targeted == face.getOpposite()) {
            return FaceRegion.CENTER;
        }
        if (targeted == Direction.UP) {
            return FaceRegion.TOP;
        }
        if (targeted == Direction.DOWN) {
            return FaceRegion.BOTTOM;
        }
        return switch (face) {
            default -> throw new MatchException(null, null);
            case Direction.NORTH, Direction.SOUTH -> {
                switch (targeted) {
                    case EAST: {
                        yield FaceRegion.RIGHT;
                    }
                    case WEST: {
                        yield FaceRegion.LEFT;
                    }
                }
                yield FaceRegion.CENTER;
            }
            case Direction.WEST, Direction.EAST -> {
                switch (targeted) {
                    case NORTH: {
                        yield FaceRegion.LEFT;
                    }
                    case SOUTH: {
                        yield FaceRegion.RIGHT;
                    }
                }
                yield FaceRegion.CENTER;
            }
            case Direction.DOWN, Direction.UP -> {
                switch (targeted) {
                    case EAST: {
                        yield FaceRegion.RIGHT;
                    }
                    case WEST: {
                        yield FaceRegion.LEFT;
                    }
                    case NORTH: {
                        yield FaceRegion.BOTTOM;
                    }
                    case SOUTH: {
                        yield FaceRegion.TOP;
                    }
                }
                yield FaceRegion.CENTER;
            }
        };
    }

    private static void addEdgeQuantized(Map<EdgeKey, UVSeg> map, Vec2 a, Vec2 b) {
        int ax = EdgeKey.quantize(a.x);
        int ay = EdgeKey.quantize(a.y);
        int bx = EdgeKey.quantize(b.x);
        int by = EdgeKey.quantize(b.y);
        int dx = Integer.signum(bx - ax);
        int dy = Integer.signum(by - ay);
        int steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay));
        if (steps == 0) {
            return;
        }
        int x = ax;
        int y = ay;
        for (int i = 0; i < steps; ++i) {
            int nx = x + dx;
            int ny = y + dy;
            EdgeKey ek = new EdgeKey(x, y, nx, ny);
            UVSeg seg = map.get(ek);
            if (seg == null) {
                map.put(ek, new UVSeg(new Vec2((float)x / 1024.0f, (float)y / 1024.0f), new Vec2((float)nx / 1024.0f, (float)ny / 1024.0f)));
            } else {
                ++seg.count;
            }
            x = nx;
            y = ny;
        }
    }

    /*
     * WARNING - void declaration
     */
    private static List<List<Vec2>> buildChains(Map<EdgeKey, UVSeg> edgeMap) {
        List<UVSeg> unitEdges = edgeMap.values().stream().filter(e -> e.count == 1).toList();
        HashMap<QuantizedUV, List<QuantizedUV>> adjacent = new HashMap<QuantizedUV, List<QuantizedUV>>();
        for (UVSeg uVSeg : unitEdges) {
            QuantizedUV quantizedUV = QuantizedUV.fromVec2f(uVSeg.a);
            QuantizedUV b = QuantizedUV.fromVec2f(uVSeg.b);
            adjacent.computeIfAbsent(quantizedUV, k -> new ArrayList()).add(b);
            adjacent.computeIfAbsent(b, k -> new ArrayList()).add(quantizedUV);
        }
        HashSet<Long> unused = new HashSet<Long>();
        for (UVSeg uVSeg : unitEdges) {
            unused.add(BlockFaceOverlay.edgeKey(QuantizedUV.fromVec2f(uVSeg.a), QuantizedUV.fromVec2f(uVSeg.b)));
        }
        ArrayList<List<Vec2>> arrayList = new ArrayList<List<Vec2>>();
        for (Object start : adjacent.keySet()) {
            List<QuantizedUV> chainQ;
            if (((List)adjacent.get(start)).size() == 2 || (chainQ = BlockFaceOverlay.walkChainFrom((QuantizedUV)start, adjacent, unused)).size() < 2) continue;
            arrayList.add(BlockFaceOverlay.toVec2f(chainQ));
        }
        while (!unused.isEmpty()) {
            QuantizedUV start = null;
            block4: for (QuantizedUV n : adjacent.keySet()) {
                for (QuantizedUV m : adjacent.getOrDefault(n, List.of())) {
                    if (!unused.contains(BlockFaceOverlay.edgeKey(n, m))) continue;
                    start = n;
                    break block4;
                }
            }
            if (start == null) break;
            List<QuantizedUV> loopQ = BlockFaceOverlay.walkChainFrom(start, adjacent, unused);
            if (loopQ.size() < 3) continue;
            loopQ.add(loopQ.getFirst());
            arrayList.add(BlockFaceOverlay.toVec2f(loopQ));
        }
        return arrayList;
    }

    private static List<QuantizedUV> walkChainFrom(QuantizedUV start, Map<QuantizedUV, List<QuantizedUV>> adjacent, Set<Long> unused) {
        ArrayList<QuantizedUV> chain = new ArrayList<QuantizedUV>(64);
        QuantizedUV prev = null;
        QuantizedUV curr = start;
        chain.add(curr);
        while (true) {
            QuantizedUV next = null;
            for (QuantizedUV cand : adjacent.getOrDefault(curr, List.of())) {
                long ek;
                if (cand.equals(prev) || !unused.remove(ek = BlockFaceOverlay.edgeKey(curr, cand))) continue;
                next = cand;
                break;
            }
            if (next == null) break;
            chain.add(next);
            prev = curr;
            curr = next;
        }
        return chain;
    }

    private static List<Vec2> toVec2f(List<QuantizedUV> nodes) {
        ArrayList<Vec2> out = new ArrayList<Vec2>(nodes.size());
        for (QuantizedUV q : nodes) {
            out.add(new Vec2((float)q.x / 1024.0f, (float)q.y / 1024.0f));
        }
        return out;
    }

    private static long edgeKey(QuantizedUV a, QuantizedUV b) {
        if (a.x > b.x || a.x == b.x && a.y > b.y) {
            QuantizedUV t = a;
            a = b;
            b = t;
        }
        return (long)a.x << 48 ^ (long)a.y << 32 ^ (long)b.x << 16 ^ (long)b.y;
    }

    public record FaceFrame(Direction face, Vec3 u, Vec3 v, Vec3 n) {
        public static FaceFrame create(Direction face) {
            return switch (face) {
                default -> throw new MatchException(null, null);
                case Direction.NORTH -> new FaceFrame(face, new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, -1.0));
                case Direction.SOUTH -> new FaceFrame(face, new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 1.0));
                case Direction.WEST -> new FaceFrame(face, new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 1.0, 0.0), new Vec3(-1.0, 0.0, 0.0));
                case Direction.EAST -> new FaceFrame(face, new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 1.0, 0.0), new Vec3(1.0, 0.0, 0.0));
                case Direction.DOWN -> new FaceFrame(face, new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, -1.0, 0.0));
                case Direction.UP -> new FaceFrame(face, new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 1.0, 0.0));
            };
        }
    }

    private static enum FaceRegion {
        CENTER,
        LEFT,
        RIGHT,
        BOTTOM,
        TOP;

    }

    private record RectUV(double u0, double v0, double u1, double v1) {
        private RectUV(double u0, double v0, double u1, double v1) {
            if (u0 <= u1) {
                this.u0 = u0;
                this.u1 = u1;
            } else {
                this.u0 = u1;
                this.u1 = u0;
            }
            if (v0 <= v1) {
                this.v0 = v0;
                this.v1 = v1;
            } else {
                this.v0 = v1;
                this.v1 = v0;
            }
        }

        boolean isDegenerate() {
            return this.u1 - this.u0 <= 1.0E-6 || this.v1 - this.v0 <= 1.0E-6;
        }
    }

    private static final class EdgeKey {
        public static final int EDGE_Q = 1024;
        final int ax;
        final int ay;
        final int bx;
        final int by;

        public EdgeKey(float ax, float ay, float bx, float by) {
            int qax = EdgeKey.quantize(ax);
            int qay = EdgeKey.quantize(ay);
            int qbx = EdgeKey.quantize(bx);
            int qby = EdgeKey.quantize(by);
            if (qax < qbx || qax == qbx && qay <= qby) {
                this.ax = qax;
                this.ay = qay;
                this.bx = qbx;
                this.by = qby;
            } else {
                this.ax = qbx;
                this.ay = qby;
                this.bx = qax;
                this.by = qay;
            }
        }

        public static int quantize(float v) {
            return Math.round(v * 1024.0f);
        }

        public boolean equals(Object o) {
            if (!(o instanceof EdgeKey)) {
                return false;
            }
            EdgeKey e = (EdgeKey)o;
            return this.ax == e.ax && this.ay == e.ay && this.bx == e.bx && this.by == e.by;
        }

        public int hashCode() {
            int h = this.ax;
            h = 31 * h + this.ay;
            h = 31 * h + this.bx;
            h = 31 * h + this.by;
            return h;
        }
    }

    private static final class UVSeg {
        final Vec2 a;
        final Vec2 b;
        int count = 1;

        UVSeg(Vec2 a, Vec2 b) {
            this.a = a;
            this.b = b;
        }
    }

    private record QuantizedUV(int x, int y) {
        public static QuantizedUV fromVec2f(Vec2 v) {
            return new QuantizedUV(EdgeKey.quantize(v.x), EdgeKey.quantize(v.y));
        }
    }
}

