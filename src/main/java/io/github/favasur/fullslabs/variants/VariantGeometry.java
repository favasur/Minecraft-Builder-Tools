package io.github.favasur.fullslabs.variants;

import io.github.favasur.fullslabs.variants.RoofSlopeBlock.Kind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

/**
 * Shared geometry for the automatically generated slab / stair / roof-slope variants.
 *
 * <p>Slabs and stairs use the exact vanilla element geometry (verified against the 1.21.1
 * blockstate/model files), so the baked render is pixel-identical to vanilla. Roof slopes use
 * custom stepped geometry; their collision shapes are built from the very same box lists, so the
 * render always matches the collision 1:1.
 */
public final class VariantGeometry {

	/** An axis-aligned box in 1/16 block units: [x1,y1,z1] -> [x2,y2,z2]. */
	public record Box(double x1, double y1, double z1, double x2, double y2, double z2) {
	}

	// ---------------------------------------------------------------- slabs

	/** Vanilla slab element boxes for a {@code type} value: bottom / top / double. */
	public static List<Box> slabBoxes(String type) {
		return switch (type) {
			case "bottom" -> List.of(new Box(0, 0, 0, 16, 8, 16));
			case "top" -> List.of(new Box(0, 8, 0, 16, 16, 16));
			default -> List.of(new Box(0, 0, 0, 16, 16, 16));
		};
	}

	// ---------------------------------------------------------------- stairs

	// Vanilla StairBlock: shapes are BOTTOM_AABB/TOP_AABB plus combinations of the four
	// 8x8x8 corner octets selected by SHAPE_BY_STATE[facing.data2d + shape.ordinal()*4].
	private static final int[] SHAPE_BY_STATE =
			{12, 5, 3, 10, 14, 13, 7, 11, 13, 7, 11, 14, 8, 4, 1, 2, 4, 1, 2, 8};

	private static final Box BOTTOM_AABB = new Box(0, 0, 0, 16, 8, 16);
	private static final Box TOP_AABB = new Box(0, 8, 0, 16, 16, 16);
	// bottom-half octets (top corners, y 8-16): bit 1 = NPN, 2 = PPN, 4 = NPP, 8 = PPP
	private static final Box[] BOTTOM_OCTETS = {
			new Box(0, 8, 0, 8, 16, 8),
			new Box(8, 8, 0, 16, 16, 8),
			new Box(0, 8, 8, 8, 16, 16),
			new Box(8, 8, 8, 16, 16, 16),
	};
	// top-half octets (bottom corners, y 0-8)
	private static final Box[] TOP_OCTETS = {
			new Box(0, 0, 0, 8, 8, 8),
			new Box(8, 0, 0, 16, 8, 8),
			new Box(0, 0, 8, 8, 8, 16),
			new Box(8, 0, 8, 16, 8, 16),
	};

	/** World-space element boxes for a vanilla stair state (identical to vanilla's collision). */
	public static List<Box> stairBoxes(StairsShape shape, Direction facing, Half half) {
		boolean top = half == Half.TOP;
		List<Box> boxes = new ArrayList<>();
		boxes.add(top ? TOP_AABB : BOTTOM_AABB);
		int index = shape.ordinal() * 4 + facing.get2DDataValue();
		int bits = SHAPE_BY_STATE[index];
		Box[] octets = top ? TOP_OCTETS : BOTTOM_OCTETS;
		for (int i = 0; i < 4; i++) {
			if ((bits & (1 << i)) != 0) {
				boxes.add(octets[i]);
			}
		}
		return boxes;
	}

	// ---------------------------------------------------------------- roof slopes

	/** Model-rotation y-angle used for a horizontal facing (matches vanilla stair blockstates). */
	public static int yDegFor(Direction facing) {
		return switch (facing) {
			case EAST -> 0;
			case SOUTH -> 90;
			case WEST -> 180;
			default -> 270; // NORTH
		};
	}

	/**
	 * Rotates a box around the block centre by a model y-rotation (clockwise from above, matching
	 * {@code BlockModelRotation}). Base geometry is authored for facing=EAST.
	 */
	public static Box rotateY(Box b, int yDeg) {
		return switch (Math.floorMod(yDeg, 360)) {
			case 90 -> new Box(16 - b.z2(), b.y1(), b.x1(), 16 - b.z1(), b.y2(), b.x2());
			case 180 -> new Box(16 - b.x2(), b.y1(), 16 - b.z2(), 16 - b.x1(), b.y2(), 16 - b.z1());
			case 270 -> new Box(b.z1(), b.y1(), 16 - b.x2(), b.z2(), b.y2(), 16 - b.x1());
			default -> b;
		};
	}

	/** Base (facing=EAST) boxes for a roof-slope kind. High edge on the east (facing) side. */
	private static List<Box> slopeBaseBoxes(Kind kind) {
		return switch (kind) {
			case SLOPE -> List.of(
					new Box(0, 0, 0, 16, 4, 16),
					new Box(4, 4, 0, 16, 8, 16),
					new Box(8, 8, 0, 16, 12, 16),
					new Box(12, 12, 0, 16, 16, 16));
			case OUTER -> List.of(
					new Box(0, 0, 0, 16, 4, 16),
					new Box(4, 4, 0, 16, 8, 12),
					new Box(8, 8, 0, 16, 12, 8),
					new Box(12, 12, 0, 16, 16, 4));
			case INNER -> List.of(
					new Box(0, 0, 0, 16, 4, 16),
					new Box(0, 4, 0, 4, 8, 16),
					new Box(4, 4, 12, 16, 8, 16),
					new Box(0, 8, 0, 8, 12, 16),
					new Box(8, 8, 8, 16, 12, 16),
					new Box(0, 12, 0, 12, 16, 16),
					new Box(12, 12, 4, 16, 16, 16));
		};
	}

	/** World-space boxes for a roof-slope state (render and collision share these). */
	public static List<Box> slopeBoxes(Kind kind, Direction facing) {
		int y = yDegFor(facing);
		List<Box> out = new ArrayList<>();
		for (Box b : slopeBaseBoxes(kind)) {
			out.add(rotateY(b, y));
		}
		return out;
	}

	// ---------------------------------------------------------------- model JSON

	/**
	 * Builds a block-model JSON for the given world-space boxes with the base block's concrete
	 * textures inlined. Faces use positional UVs (texture tiles over the 16x16x16 cube) and are
	 * culled when fully hidden by another element, so there is no z-fighting between coplanar
	 * faces and no visible interior holes.
	 */
	public static String modelJson(List<Box> boxes, String top, String bottom, String side) {
		boolean[][][] occupied = new boolean[16][16][16];
		for (Box b : boxes) {
			for (int x = (int) b.x1(); x < b.x2(); x++) {
				for (int y = (int) b.y1(); y < b.y2(); y++) {
					for (int z = (int) b.z1(); z < b.z2(); z++) {
						occupied[x][y][z] = true;
					}
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("{\"textures\":{\"particle\":\"").append(side).append("\"},");
		sb.append("\"display\":{"
				+ "\"thirdperson_righthand\":{\"rotation\":[75,45,0],\"translation\":[0,2.5,0],\"scale\":[0.375,0.375,0.375]},"
				+ "\"thirdperson_lefthand\":{\"rotation\":[75,45,0],\"translation\":[0,2.5,0],\"scale\":[0.375,0.375,0.375]},"
				+ "\"firstperson_righthand\":{\"rotation\":[0,45,0],\"translation\":[0,0,0],\"scale\":[0.4,0.4,0.4]},"
				+ "\"firstperson_lefthand\":{\"rotation\":[0,225,0],\"translation\":[0,0,0],\"scale\":[0.4,0.4,0.4]},"
				+ "\"gui\":{\"rotation\":[30,225,0],\"translation\":[0,0,0],\"scale\":[0.625,0.625,0.625]},"
				+ "\"head\":{\"rotation\":[0,0,0],\"translation\":[0,0,0],\"scale\":[1,1,1]},"
				+ "\"fixed\":{\"rotation\":[0,0,0],\"translation\":[0,0,0],\"scale\":[0.5,0.5,0.5]},"
				+ "\"ground\":{\"rotation\":[0,0,0],\"translation\":[0,3,0],\"scale\":[0.25,0.25,0.25]}},");
		sb.append("\"elements\":[");
		boolean first = true;
		for (Box b : boxes) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(element(b, occupied, top, bottom, side));
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String element(Box b, boolean[][][] occupied, String top, String bottom, String side) {
		int x1 = (int) b.x1(), y1 = (int) b.y1(), z1 = (int) b.z1();
		int x2 = (int) b.x2(), y2 = (int) b.y2(), z2 = (int) b.z2();
		StringBuilder sb = new StringBuilder();
		sb.append("{\"from\":[").append(x1).append(',').append(y1).append(',').append(z1)
				.append("],\"to\":[").append(x2).append(',').append(y2).append(',').append(z2)
				.append("],\"faces\":{");
		// A face is emitted unless every cell immediately outside it is occupied by another element
		// (fully hidden interior face) or the face lies on the block boundary (then it is culled).
		// up
		if (y2 == 16 || !covered(occupied, x1, x2, y2, y2 + 1, z1, z2)) {
			face(sb, "up", x1, z1, x2, z2, top, y2 == 16 ? "up" : null);
		}
		// down
		if (y1 == 0 || !covered(occupied, x1, x2, y1 - 1, y1, z1, z2)) {
			face(sb, "down", x1, 16 - z2, x2, 16 - z1, bottom, y1 == 0 ? "down" : null);
		}
		// north / south
		if (z1 == 0 || !covered(occupied, x1, x2, y1, y2, z1 - 1, z1)) {
			face(sb, "north", x1, 16 - y2, x2, 16 - y1, side, z1 == 0 ? "north" : null);
		}
		if (z2 == 16 || !covered(occupied, x1, x2, y1, y2, z2, z2 + 1)) {
			face(sb, "south", x1, 16 - y2, x2, 16 - y1, side, z2 == 16 ? "south" : null);
		}
		// west / east
		if (x1 == 0 || !covered(occupied, x1 - 1, x1, y1, y2, z1, z2)) {
			face(sb, "west", z1, 16 - y2, z2, 16 - y1, side, x1 == 0 ? "west" : null);
		}
		if (x2 == 16 || !covered(occupied, x2, x2 + 1, y1, y2, z1, z2)) {
			face(sb, "east", z1, 16 - y2, z2, 16 - y1, side, x2 == 16 ? "east" : null);
		}
		sb.append("}}");
		return sb.toString();
	}

	/** True when every cell in the given (in-bounds) region is occupied. */
	private static boolean covered(boolean[][][] occ, int x1, int x2, int y1, int y2, int z1, int z2) {
		for (int x = x1; x < x2; x++) {
			for (int y = y1; y < y2; y++) {
				for (int z = z1; z < z2; z++) {
					if (!occ[x][y][z]) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static void face(StringBuilder sb, String dir, int u1, int v1, int u2, int v2,
			String texture, String cull) {
		sb.append('"').append(dir).append("\":{\"uv\":[").append(u1).append(',').append(v1)
				.append(',').append(u2).append(',').append(v2).append("],\"texture\":\"")
				.append(texture).append('"');
		if (cull != null) {
			sb.append(",\"cullface\":\"").append(cull).append('"');
		}
		sb.append('}');
	}

	private VariantGeometry() {
	}
}
