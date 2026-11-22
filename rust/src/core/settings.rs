//! The Creative settings the tools read, in one place.
//!
//! The Java mod keeps a `BuilderSettings` object and a 464-line panel built onto the creative
//! inventory. This is the value half of that: every number the port's tools already take — a wall's
//! height and batter, a golem's fetching rule, a path's width and a territory's border — stored as
//! one struct, with the conversions into the core types the tools consume
//! ([`WallSpec`](super::wall::WallSpec), [`GolemRules`](super::golem::GolemRules),
//! [`PathStyle`](super::path::PathStyle), [`Territory`](super::polygon::Territory)).
//!
//! Two rules keep the panel honest:
//!
//! * **One range per setting, enforced on every write.** `set_text` parses *and* clamps, so a field
//!   the player types `9999` into cannot make a 9,999-course wall; the range is the same one the
//!   label shows.
//! * **The panel never invents a behaviour.** What a setting means is decided by the core type it
//!   feeds; this module only carries the number across.

use super::golem::{GolemKind, GolemRules};
use super::path::{PathBands, PathStyle};
use super::polygon::{Border, Territory};
use super::wall::WallSpec;
use super::world::BlockId;

/// Which value a row of the panel edits.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Setting {
    WallHeight,
    WallThickness,
    WallBatter,
    GolemFetching,
    GolemKind,
    PathWidth,
    PathHeight,
    BorderWidth,
    TerritoryDepth,
    Reach,
}

impl Setting {
    /// Every row, in the order the panel shows them.
    pub const ALL: [Setting; 10] = [
        Setting::WallHeight,
        Setting::WallThickness,
        Setting::WallBatter,
        Setting::GolemFetching,
        Setting::GolemKind,
        Setting::PathWidth,
        Setting::PathHeight,
        Setting::BorderWidth,
        Setting::TerritoryDepth,
        Setting::Reach,
    ];

    /// The stable id a saved file or a debug line uses.
    pub const fn key(self) -> &'static str {
        match self {
            Setting::WallHeight => "wall_height",
            Setting::WallThickness => "wall_thickness",
            Setting::WallBatter => "wall_batter",
            Setting::GolemFetching => "golem_fetching",
            Setting::GolemKind => "golem_kind",
            Setting::PathWidth => "path_width",
            Setting::PathHeight => "path_height",
            Setting::BorderWidth => "border_width",
            Setting::TerritoryDepth => "territory_depth",
            Setting::Reach => "reach",
        }
    }

    pub fn from_key(key: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|setting| setting.key() == key)
    }

    /// What the panel writes beside the field.
    pub const fn label(self) -> &'static str {
        match self {
            Setting::WallHeight => "Wall height",
            Setting::WallThickness => "Wall thickness",
            Setting::WallBatter => "Wall batter",
            Setting::GolemFetching => "Golem fetching",
            Setting::GolemKind => "Golem",
            Setting::PathWidth => "Path width",
            Setting::PathHeight => "Path height",
            Setting::BorderWidth => "Border width",
            Setting::TerritoryDepth => "Territory depth",
            Setting::Reach => "Reach",
        }
    }

    /// The accepted range of a number, which the field's suggestion shows.
    pub const fn range(self) -> Option<(f64, f64)> {
        match self {
            Setting::WallHeight => Some((1.0, 64.0)),
            Setting::WallThickness => Some((0.5, 8.0)),
            Setting::WallBatter => Some((0.0, 2.0)),
            Setting::GolemFetching => None,
            Setting::GolemKind => None,
            Setting::PathWidth => Some((1.0, 32.0)),
            Setting::PathHeight => Some((1.0, 32.0)),
            Setting::BorderWidth => Some((0.5, 16.0)),
            Setting::TerritoryDepth => Some((1.0, 32.0)),
            Setting::Reach => Some((8.0, 256.0)),
        }
    }

    /// Whether the row is a toggle rather than a number.
    pub const fn is_toggle(self) -> bool {
        matches!(self, Setting::GolemFetching)
    }

    /// Whether the row cycles through named choices.
    pub const fn is_choice(self) -> bool {
        matches!(self, Setting::GolemKind)
    }
}

/// Why a typed value was not accepted. Refused text never reaches the settings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SettingError {
    /// The text is not a number (or not one of the words the row accepts).
    NotANumber,
    /// The value was outside [`Setting::range`]; nothing was stored.
    OutOfRange,
    /// The setting has no meaning for that operation (a number is not toggled).
    WrongKind,
}

/// Every value the tools read.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BuilderSettings {
    /// Courses of a wall polygon, `1..=64`.
    pub wall_height: u32,
    /// Base band width of a wall, in blocks.
    pub wall_thickness: f64,
    /// How much thinner each course is: the wedge.
    pub wall_batter: f64,
    /// `/gamerule GolemFetching` as the player sees it.
    pub golem_fetching: bool,
    /// Which golem a spawn makes.
    pub golem_kind: GolemKind,
    pub path_width: f64,
    pub path_height: i32,
    pub border_width: f64,
    pub territory_depth: i32,
    /// How far a builder may reach, in blocks.
    pub reach: f64,
}

impl BuilderSettings {
    /// The values a fresh installation starts with — the same numbers the core's constructors
    /// default to, so a tool used without opening the panel behaves exactly as before.
    pub const DEFAULT: Self = Self {
        wall_height: 4,
        wall_thickness: 1.0,
        wall_batter: 0.0,
        golem_fetching: true,
        golem_kind: GolemKind::Iron,
        path_width: 5.0,
        path_height: 2,
        border_width: 1.0,
        territory_depth: 1,
        reach: 64.0,
    };

    /// The current text of a row's field, or the word its button shows.
    pub fn value(&self, setting: Setting) -> String {
        match setting {
            Setting::WallHeight => self.wall_height.to_string(),
            Setting::WallThickness => trim(self.wall_thickness),
            Setting::WallBatter => trim(self.wall_batter),
            Setting::GolemFetching => on_off(self.golem_fetching).to_string(),
            Setting::GolemKind => kind_name(self.golem_kind).to_string(),
            Setting::PathWidth => trim(self.path_width),
            Setting::PathHeight => self.path_height.to_string(),
            Setting::BorderWidth => trim(self.border_width),
            Setting::TerritoryDepth => self.territory_depth.to_string(),
            Setting::Reach => trim(self.reach),
        }
    }

    /// Reads a typed value, clamped to the row's range.
    ///
    /// `OutOfRange` and `NotANumber` both store nothing: a typo must not silently become the
    /// nearest legal number, because then the field and the tools disagree about what was asked.
    pub fn set_text(&mut self, setting: Setting, text: &str) -> Result<(), SettingError> {
        let text = text.trim();
        match setting {
            Setting::GolemFetching => {
                let value = parse_bool(text).ok_or(SettingError::NotANumber)?;
                self.golem_fetching = value;
                Ok(())
            }
            Setting::GolemKind => {
                let value = parse_kind(text).ok_or(SettingError::NotANumber)?;
                self.golem_kind = value;
                Ok(())
            }
            _ => {
                let (min, max) = setting.range().ok_or(SettingError::WrongKind)?;
                let value: f64 = text.parse().map_err(|_| SettingError::NotANumber)?;
                if !value.is_finite() || value < min || value > max {
                    return Err(SettingError::OutOfRange);
                }
                self.store(setting, value)
            }
        }
    }

    /// Writes an already-parsed number, clamping it into range (used by the ± steps).
    pub fn set_number(&mut self, setting: Setting, value: f64) -> Result<(), SettingError> {
        let (min, max) = setting.range().ok_or(SettingError::WrongKind)?;
        self.store(setting, value.clamp(min, max))
    }

    fn store(&mut self, setting: Setting, value: f64) -> Result<(), SettingError> {
        match setting {
            Setting::WallHeight => self.wall_height = value.round() as u32,
            Setting::WallThickness => self.wall_thickness = value,
            Setting::WallBatter => self.wall_batter = value,
            Setting::PathWidth => self.path_width = value,
            Setting::PathHeight => self.path_height = value.round() as i32,
            Setting::BorderWidth => self.border_width = value,
            Setting::TerritoryDepth => self.territory_depth = value.round() as i32,
            Setting::Reach => self.reach = value,
            Setting::GolemFetching | Setting::GolemKind => return Err(SettingError::WrongKind),
        }
        Ok(())
    }

    /// What a button press does: a toggle flips, a choice moves to the next, a number steps.
    pub fn press(&mut self, setting: Setting) -> Result<(), SettingError> {
        match setting {
            Setting::GolemFetching => {
                self.golem_fetching = !self.golem_fetching;
                Ok(())
            }
            Setting::GolemKind => {
                self.golem_kind = match self.golem_kind {
                    GolemKind::Iron => GolemKind::Copper,
                    GolemKind::Copper => GolemKind::Iron,
                };
                Ok(())
            }
            _ => {
                let step = step_of(setting);
                let current: f64 = self.value(setting).parse().unwrap_or(0.0);
                self.set_number(setting, current + step)
            }
        }
    }

    /// A ± button: one step of the row, clamped.
    pub fn adjust(&mut self, setting: Setting, steps: f64) -> Result<(), SettingError> {
        let current: f64 = self.value(setting).parse().map_err(|_| SettingError::WrongKind)?;
        self.set_number(setting, current + step_of(setting) * steps)
    }

    /// The wall polygon spec these settings describe.
    pub fn wall_spec(&self, block: BlockId) -> WallSpec {
        WallSpec {
            block,
            height: self.wall_height,
            thickness: self.wall_thickness,
            batter: self.wall_batter,
            cap: None,
        }
    }

    /// The fetching rule these settings describe.
    pub fn golem_rules(&self) -> GolemRules {
        GolemRules {
            fetching: self.golem_fetching,
        }
    }

    /// A path style with this width and height over the given bands.
    pub fn path_style(&self, bands: PathBands) -> PathStyle {
        PathStyle {
            bands,
            width: self.path_width,
            height: self.path_height,
            edge_width: 0.0,
        }
    }

    /// A territory with this border width and depth; hatching stays the tool's choice.
    pub fn territory(&self, fill: BlockId, border_block: BlockId) -> Territory {
        Territory {
            border: Some(Border {
                block: border_block,
                width: self.border_width,
            }),
            fill: Some(fill),
            hatch: None,
            depth: self.territory_depth,
        }
    }
}

impl Default for BuilderSettings {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// How far a ± press moves a row.
const fn step_of(setting: Setting) -> f64 {
    match setting {
        Setting::WallHeight => 1.0,
        Setting::WallThickness => 0.25,
        Setting::WallBatter => 0.25,
        Setting::PathWidth => 0.5,
        Setting::PathHeight => 1.0,
        Setting::BorderWidth => 0.5,
        Setting::TerritoryDepth => 1.0,
        Setting::Reach => 16.0,
        Setting::GolemFetching | Setting::GolemKind => 0.0,
    }
}

fn on_off(value: bool) -> &'static str {
    if value { "on" } else { "off" }
}

fn kind_name(kind: GolemKind) -> &'static str {
    match kind {
        GolemKind::Iron => "iron",
        GolemKind::Copper => "copper",
    }
}

fn parse_bool(text: &str) -> Option<bool> {
    match text.to_ascii_lowercase().as_str() {
        "true" | "on" | "yes" | "1" => Some(true),
        "false" | "off" | "no" | "0" => Some(false),
        _ => None,
    }
}

fn parse_kind(text: &str) -> Option<GolemKind> {
    match text.to_ascii_lowercase().as_str() {
        "iron" | "iron_golem" | "iron golem" => Some(GolemKind::Iron),
        "copper" | "copper_golem" | "copper golem" => Some(GolemKind::Copper),
        _ => None,
    }
}

/// A number as a field shows it: no trailing zeros, so `1.0` reads as `1`.
fn trim(value: f64) -> String {
    let text = format!("{value:.2}");
    let text = text.trim_end_matches('0').trim_end_matches('.');
    if text.is_empty() { "0".to_string() } else { text.to_string() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::path::PathBands;

    const STONE: BlockId = BlockId::new(1);
    const DIRT: BlockId = BlockId::new(2);

    #[test]
    fn the_defaults_match_what_the_core_constructors_produce() {
        let settings = BuilderSettings::DEFAULT;
        let spec = settings.wall_spec(STONE);
        assert_eq!(spec.height, settings.wall_height);
        assert_eq!(spec.thickness, 1.0, "the plain one-block wall");
        assert_eq!(spec.batter, 0.0, "a straight wall");
        assert_eq!(settings.golem_rules(), GolemRules::DEFAULT);
        assert_eq!(settings.golem_kind, GolemKind::Iron);
        assert_eq!(settings.territory_depth, 1);
    }

    #[test]
    fn every_row_round_trips_through_its_own_text() {
        let mut settings = BuilderSettings::DEFAULT;
        for setting in Setting::ALL {
            let text = settings.value(setting);
            let before = settings;
            if setting.is_toggle() || setting.is_choice() {
                // A toggle/choice accepts its own word without changing anything.
                settings.set_text(setting, &text).unwrap();
                assert_eq!(settings, before, "{} did not round-trip", setting.key());
                continue;
            }
            settings
                .set_text(setting, &text)
                .unwrap_or_else(|err| panic!("{} rejected its own text {text:?}: {err:?}", setting.key()));
            assert_eq!(settings, before, "{} did not round-trip", setting.key());
        }
    }

    #[test]
    fn a_typed_number_is_clamped_only_when_it_is_in_range() {
        let mut settings = BuilderSettings::DEFAULT;
        assert_eq!(settings.set_text(Setting::WallHeight, "12"), Ok(()));
        assert_eq!(settings.wall_height, 12);

        // Out of range is refused, and the value stays what it was.
        assert_eq!(
            settings.set_text(Setting::WallHeight, "9999"),
            Err(SettingError::OutOfRange)
        );
        assert_eq!(settings.wall_height, 12);
        assert_eq!(
            settings.set_text(Setting::WallBatter, "-1"),
            Err(SettingError::OutOfRange)
        );
        assert_eq!(settings.wall_batter, 0.0);

        // Junk is refused too, never defaulted.
        assert_eq!(
            settings.set_text(Setting::Reach, "far"),
            Err(SettingError::NotANumber)
        );
        assert_eq!(settings.reach, 64.0);
    }

    #[test]
    fn words_are_accepted_for_the_toggle_and_the_choice() {
        let mut settings = BuilderSettings::DEFAULT;
        for word in ["false", "off", "no", "0"] {
            settings.set_text(Setting::GolemFetching, word).unwrap();
            assert!(!settings.golem_fetching, "{word}");
        }
        for word in ["true", "on", "yes", "1"] {
            settings.set_text(Setting::GolemFetching, word).unwrap();
            assert!(settings.golem_fetching, "{word}");
        }
        settings.set_text(Setting::GolemKind, "Copper").unwrap();
        assert_eq!(settings.golem_kind, GolemKind::Copper);
        assert_eq!(
            settings.set_text(Setting::GolemKind, "gold"),
            Err(SettingError::NotANumber)
        );
        assert_eq!(settings.golem_kind, GolemKind::Copper);
    }

    #[test]
    fn a_press_toggles_or_cycles_and_the_labels_follow() {
        let mut settings = BuilderSettings::DEFAULT;
        assert_eq!(settings.value(Setting::GolemFetching), "on");
        settings.press(Setting::GolemFetching).unwrap();
        assert!(!settings.golem_fetching);
        assert_eq!(settings.value(Setting::GolemFetching), "off");
        assert_eq!(settings.value(Setting::GolemKind), "iron");
        settings.press(Setting::GolemKind).unwrap();
        assert_eq!(settings.value(Setting::GolemKind), "copper");
        settings.press(Setting::GolemKind).unwrap();
        assert_eq!(settings.value(Setting::GolemKind), "iron");
    }

    #[test]
    fn steps_move_a_row_by_its_own_increment_and_stop_at_the_range() {
        let mut settings = BuilderSettings::DEFAULT;
        settings.adjust(Setting::WallHeight, 2.0).unwrap();
        assert_eq!(settings.wall_height, 6);
        settings.adjust(Setting::WallThickness, 2.0).unwrap();
        assert_eq!(settings.wall_thickness, 1.5);

        // Clamped at the top rather than refused: a held ± button is a command, not a typo.
        settings.adjust(Setting::WallHeight, 1000.0).unwrap();
        assert_eq!(settings.wall_height, 64);
        settings.adjust(Setting::WallHeight, -1000.0).unwrap();
        assert_eq!(settings.wall_height, 1);

        assert_eq!(settings.adjust(Setting::GolemFetching, 1.0), Err(SettingError::WrongKind));
        assert_eq!(settings.set_number(Setting::GolemKind, 3.0), Err(SettingError::WrongKind));
    }

    #[test]
    fn the_derived_core_values_carry_every_edited_number() {
        let mut settings = BuilderSettings::DEFAULT;
        settings.set_text(Setting::WallHeight, "9").unwrap();
        settings.set_text(Setting::WallThickness, "2").unwrap();
        settings.set_text(Setting::WallBatter, "0.5").unwrap();
        settings.set_text(Setting::PathWidth, "7.5").unwrap();
        settings.set_text(Setting::PathHeight, "3").unwrap();
        settings.set_text(Setting::BorderWidth, "2.5").unwrap();
        settings.set_text(Setting::TerritoryDepth, "4").unwrap();
        settings.press(Setting::GolemFetching).unwrap();

        let spec = settings.wall_spec(STONE);
        assert_eq!((spec.block, spec.height, spec.thickness, spec.batter), (STONE, 9, 2.0, 0.5));
        assert_eq!(settings.golem_rules().fetching, false);

        let style = settings.path_style(PathBands::road(STONE, DIRT, Some(DIRT)));
        assert_eq!(style.width, 7.5);
        assert_eq!(style.height, 3);

        let territory = settings.territory(STONE, DIRT);
        assert_eq!(territory.depth, 4);
        assert_eq!(territory.border.unwrap().width, 2.5);
        assert_eq!(territory.fill, Some(STONE));
    }

    #[test]
    fn keys_are_stable_and_unique() {
        let mut keys: Vec<&str> = Setting::ALL.iter().map(|s| s.key()).collect();
        keys.sort_unstable();
        let count = keys.len();
        keys.dedup();
        assert_eq!(keys.len(), count);
        assert_eq!(Setting::from_key("wall_height"), Some(Setting::WallHeight));
        assert_eq!(Setting::from_key("nope"), None);
    }
}
