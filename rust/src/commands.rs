//! Command parsing for the WorldEdit-style command set, ported from `server/BuilderCommand.java`.
//!
//! `parse` turns the literal path and the positional arguments into one variant, and `direction`
//! resolves the `up/down/north/...` words the Java mod accepts. Block arguments stay as text: only
//! the host can turn `minecraft:stone[axis=y]` into a block state id.

use crate::core::pos::BlockPos;

/// One parsed builder command. `Eq` is gone because a road's width is a fractional block count.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Parsed<'a> {
    Wand,
    Tools,
    Pos1,
    Pos2,
    Sel,
    Set { block: &'a str },
    ReplaceAll { to: &'a str },
    Replace { from: &'a str, to: &'a str },
    Walls { block: &'a str },
    Outline { block: &'a str },
    Hollow { block: &'a str },
    Faces { block: &'a str },
    Overlay { block: &'a str },
    Center { block: &'a str },
    Copy,
    Cut,
    Paste,
    Move { count: i32, dir: &'a str },
    Stack { count: i32, dir: &'a str },
    Expand { amount: i32, dir: &'a str },
    Contract { amount: i32, dir: &'a str },
    Shift { amount: i32, dir: &'a str },
    Undo,
    Redo,
    Clear,
    ClearInventory,
    ClearEntities { radius: Option<i32> },
    SmoothTerrain,
    /// A road swept along the drawn path: `surface` on top, `filler` below, `width` wide.
    Road {
        surface: &'a str,
        filler: &'a str,
        width: f64,
        height: i32,
    },
    /// A one-block linear object: a fence line, a path, a cable run.
    Fence { block: &'a str },
    /// A polygonal territory: a border of `border` and an interior of `fill`.
    Territory { border: &'a str, fill: &'a str },
}

/// Parses a command invocation. `literals` is the literal path (`["set"]`, `["replace","from"]`)
/// and `args` the positional arguments in parse order.
pub fn parse<'a>(literals: &[String], args: &[&'a str]) -> Option<Parsed<'a>> {
    let name = literals.first()?.as_str();
    let arg = |i: usize| args.get(i).copied();

    Some(match name {
        "wand" => Parsed::Wand,
        "tools" => Parsed::Tools,
        "pos1" => Parsed::Pos1,
        "pos2" => Parsed::Pos2,
        "sel" => Parsed::Sel,
        "set" => Parsed::Set { block: arg(0)? },
        "replace" => match (arg(0), arg(1)) {
            // `/replace <from> <to>` has two arguments; `/replace <to>` has one.
            (Some(from), Some(to)) => Parsed::Replace { from, to },
            (Some(to), None) => Parsed::ReplaceAll { to },
            _ => return None,
        },
        "walls" => Parsed::Walls { block: arg(0)? },
        "outline" => Parsed::Outline { block: arg(0)? },
        "hollow" => Parsed::Hollow { block: arg(0)? },
        "faces" => Parsed::Faces { block: arg(0)? },
        "overlay" => Parsed::Overlay { block: arg(0)? },
        "center" => Parsed::Center { block: arg(0)? },
        "copy" => Parsed::Copy,
        "cut" => Parsed::Cut,
        "paste" => Parsed::Paste,
        "move" => Parsed::Move {
            count: arg(0)?.parse().ok()?,
            dir: arg(1).unwrap_or("me"),
        },
        "stack" => Parsed::Stack {
            count: arg(0)?.parse().ok()?,
            dir: arg(1).unwrap_or("me"),
        },
        "expand" => Parsed::Expand {
            amount: arg(0).unwrap_or("1").parse().ok()?,
            dir: arg(1).unwrap_or("me"),
        },
        "contract" => Parsed::Contract {
            amount: arg(0).unwrap_or("1").parse().ok()?,
            dir: arg(1).unwrap_or("me"),
        },
        "shift" => Parsed::Shift {
            amount: arg(0)?.parse().ok()?,
            dir: arg(1).unwrap_or("me"),
        },
        "undo" => Parsed::Undo,
        "redo" => Parsed::Redo,
        "clear" | "clearblocks" => Parsed::Clear,
        "clearinventory" => Parsed::ClearInventory,
        "clearentities" => Parsed::ClearEntities {
            radius: match arg(0) {
                Some(text) => Some(text.parse().ok()?),
                None => None,
            },
        },
        "smoothterrain" => Parsed::SmoothTerrain,
        "road" => Parsed::Road {
            surface: arg(0)?,
            filler: arg(1).unwrap_or(arg(0)?),
            width: arg(2).unwrap_or("3").parse().ok()?,
            height: arg(3).unwrap_or("1").parse().ok()?,
        },
        "fence" | "path" => Parsed::Fence { block: arg(0)? },
        "territory" | "poly" => Parsed::Territory {
            border: arg(0)?,
            fill: arg(1).unwrap_or(arg(0)?),
        },
        _ => return None,
    })
}

/// Every usage the mod registers, in the Java mod's order.
pub const USAGES: &[&str] = &[
    "wand",
    "tools",
    "pos1",
    "pos2",
    "sel",
    "set <block>",
    "replace <to>",
    "replace <from> <to>",
    "walls <block>",
    "outline <block>",
    "hollow <block>",
    "faces <block>",
    "overlay <block>",
    "center <block>",
    "copy",
    "cut",
    "paste",
    "move <count> [direction]",
    "stack <count> [direction]",
    "expand [amount] [direction]",
    "contract [amount] [direction]",
    "shift <amount> [direction]",
    "undo",
    "redo",
    "clear",
    "clearblocks",
    "clearinventory",
    "clearentities [radius]",
    "smoothterrain",
    "road <surface> [filler] [width] [height]",
    "fence <block>",
    "path <block>",
    "territory <border> [fill]",
];

/// Resolves a WorldEdit-style direction word. `look` is the player's horizontal facing, used for
/// `me` and for any word the mod does not know.
pub fn direction(word: &str, look: BlockPos) -> BlockPos {
    match word.to_ascii_lowercase().as_str() {
        "up" | "u" => BlockPos::new(0, 1, 0),
        "down" | "d" => BlockPos::new(0, -1, 0),
        "north" | "n" => BlockPos::new(0, 0, -1),
        "south" | "s" => BlockPos::new(0, 0, 1),
        "west" | "w" => BlockPos::new(-1, 0, 0),
        "east" | "e" => BlockPos::new(1, 0, 0),
        _ => look,
    }
}

/// The horizontal direction from a player's yaw, as `BuilderCommand.horizontalLook` computes it.
pub fn horizontal_look(yaw_degrees: f32) -> BlockPos {
    let yaw = yaw_degrees.to_radians();
    let x = (-yaw.sin()).round() as i32;
    let z = (-yaw.cos()).round() as i32;
    BlockPos::new(x, 0, z)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lits(path: &[&str]) -> Vec<String> {
        path.iter().map(|s| (*s).to_string()).collect()
    }

    #[test]
    fn parses_the_single_and_double_argument_replace_forms() {
        let path = lits(&["replace"]);
        assert_eq!(
            parse(&path, &["minecraft:stone"]),
            Some(Parsed::ReplaceAll {
                to: "minecraft:stone"
            })
        );
        assert_eq!(
            parse(&path, &["minecraft:dirt", "minecraft:stone"]),
            Some(Parsed::Replace {
                from: "minecraft:dirt",
                to: "minecraft:stone"
            })
        );
    }

    #[test]
    fn optional_direction_defaults_to_the_facing() {
        let path = lits(&["move"]);
        assert_eq!(
            parse(&path, &["3"]),
            Some(Parsed::Move { count: 3, dir: "me" })
        );
        assert_eq!(
            parse(&path, &["3", "up"]),
            Some(Parsed::Move {
                count: 3,
                dir: "up"
            })
        );
    }

    #[test]
    fn expand_and_contract_default_their_amount() {
        let path = lits(&["expand"]);
        assert_eq!(
            parse(&path, &[]),
            Some(Parsed::Expand {
                amount: 1,
                dir: "me"
            })
        );
        let path = lits(&["contract"]);
        assert_eq!(
            parse(&path, &["4", "north"]),
            Some(Parsed::Contract {
                amount: 4,
                dir: "north"
            })
        );
    }

    #[test]
    fn rejects_unknown_names_and_missing_blocks() {
        let path = lits(&["set"]);
        assert_eq!(parse(&path, &[]), None);
        let path = lits(&["nonsense"]);
        assert_eq!(parse(&path, &[]), None);
    }

    #[test]
    fn clear_aliases_and_direction_words() {
        let path = lits(&["clearblocks"]);
        assert_eq!(parse(&path, &[]), Some(Parsed::Clear));
        assert_eq!(direction("n", BlockPos::new(0, 0, 0)), BlockPos::new(0, 0, -1));
        assert_eq!(direction("e", BlockPos::new(0, 0, 0)), BlockPos::new(1, 0, 0));
        assert_eq!(
            direction("sideways", BlockPos::new(0, 0, 1)),
            BlockPos::new(0, 0, 1)
        );
    }

    #[test]
    fn linear_and_territory_commands_parse_with_defaults() {
        let path = lits(&["road"]);
        assert_eq!(
            parse(&path, &["minecraft:gravel"]),
            Some(Parsed::Road {
                surface: "minecraft:gravel",
                filler: "minecraft:gravel",
                width: 3.0,
                height: 1,
            })
        );
        assert_eq!(
            parse(&path, &["minecraft:stone_bricks", "minecraft:cobblestone", "5", "2"]),
            Some(Parsed::Road {
                surface: "minecraft:stone_bricks",
                filler: "minecraft:cobblestone",
                width: 5.0,
                height: 2,
            })
        );

        let path = lits(&["fence"]);
        assert_eq!(
            parse(&path, &["minecraft:oak_fence"]),
            Some(Parsed::Fence {
                block: "minecraft:oak_fence"
            })
        );
        let path = lits(&["path"]);
        assert_eq!(
            parse(&path, &["minecraft:dirt_path"]),
            Some(Parsed::Fence {
                block: "minecraft:dirt_path"
            })
        );

        let path = lits(&["territory"]);
        assert_eq!(
            parse(&path, &["minecraft:stone"]),
            Some(Parsed::Territory {
                border: "minecraft:stone",
                fill: "minecraft:stone"
            })
        );
        assert_eq!(
            parse(&path, &["minecraft:stone", "minecraft:grass_block"]),
            Some(Parsed::Territory {
                border: "minecraft:stone",
                fill: "minecraft:grass_block"
            })
        );
    }

    #[test]
    fn yaw_zero_faces_north() {
        assert_eq!(horizontal_look(0.0), BlockPos::new(0, 0, -1));
        assert_eq!(horizontal_look(90.0), BlockPos::new(-1, 0, 0));
    }
}
