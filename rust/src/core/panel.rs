//! The Creative settings panel's layout: rows, rects and the text each control shows.
//!
//! The Java panel is drawn onto the creative inventory: a dark-blue column on the empty right-hand
//! side, the inventory's own 195 pixels untouched. The arithmetic is the same here — the panel takes
//! the right edge of whatever rectangle the screen reports — but it is pure, so the whole layout is
//! unit-tested and the client glue only copies rects and strings into widgets.
//!
//! A row is a **field** or a **button**. Horizon's widget set has buttons and text fields but no
//! sliders (Java's numeric rows are sliders), so a number is typed into its field — with its range as
//! the field's suggestion — and a toggle or a named choice is a button that flips or cycles when
//! pressed. That is the one visible difference from the Java screen, and it is a difference of
//! control, not of value: both write the same [`BuilderSettings`].

use super::settings::{BuilderSettings, Setting};

/// The panel's background, `0xAARRGGBB`: the Java screen's dark blue at 82 % opacity.
pub const PANEL_BG: u32 = 0xD0_10_1E_3A;
/// The title's colour: bright yellow, as the territory preview's outline.
pub const TITLE_COLOR: u32 = 0xFF_FF_D4_00;
/// A row's label.
pub const LABEL_COLOR: u32 = 0xFF_E0_E0_E0;
/// The panel's title.
pub const TITLE: &str = "Builder Tools";

/// Height of one row's control, in GUI pixels (vanilla's widget height).
pub const ROW_HEIGHT: f32 = 20.0;
/// Space between two rows.
pub const ROW_GAP: f32 = 3.0;
/// Space inside the panel's edges.
pub const PADDING: f32 = 8.0;
/// Height of the title line.
pub const TITLE_HEIGHT: f32 = 12.0;
/// Width of a text field.
pub const FIELD_WIDTH: f32 = 56.0;
/// Width of a button.
pub const BUTTON_WIDTH: f32 = 78.0;
/// The panel never gets narrower than this, or wider than the next value, however wide the window.
pub const MIN_PANEL_WIDTH: f32 = 150.0;
pub const MAX_PANEL_WIDTH: f32 = 260.0;
/// How much of the window's width the panel asks for, when that falls between the two bounds.
pub const WIDTH_FRACTION: f32 = 0.28;

/// What kind of control a row has.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RowKind {
    /// A text field: the value is typed.
    Field,
    /// A button: a toggle flips, a choice cycles.
    Button,
}

/// One row of the panel.
#[derive(Clone, Debug, PartialEq)]
pub struct PanelRow {
    pub setting: Setting,
    /// What the row is called, shown left of its control.
    pub label: String,
    /// The range or the accepted words, shown as the field's suggestion.
    pub hint: String,
    pub kind: RowKind,
    /// The control's `[x, y, w, h]` in GUI pixels.
    pub rect: [f32; 4],
    /// Where the label's text starts.
    pub label_at: [f32; 2],
    /// The field's text, or the button's caption.
    pub value: String,
}

impl PanelRow {
    /// Whether `at` is on this row's control.
    pub fn contains(&self, at: [f32; 2]) -> bool {
        let [x, y, w, h] = self.rect;
        at[0] >= x && at[0] < x + w && at[1] >= y && at[1] < y + h
    }
}

/// The laid-out panel for one window and one set of settings.
#[derive(Clone, Debug, PartialEq)]
pub struct Panel {
    /// The panel's own `[x, y, w, h]`, the area its background covers.
    pub rect: [f32; 4],
    pub title: String,
    pub rows: Vec<PanelRow>,
    /// Rows that did not fit in the window's height.
    pub omitted: usize,
}

impl Panel {
    /// Lays the panel out on the right-hand side of `window`.
    ///
    /// The window rect is the screen's container panel — for the creative inventory the whole window
    /// — so the panel lands where the Java screen puts it: flush against the right edge, clear of the
    /// inventory by the same padding.
    pub fn layout(window: [f32; 4], settings: &BuilderSettings) -> Panel {
        let [wx, wy, ww, wh] = window;
        let width = (ww * WIDTH_FRACTION).clamp(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        let x = wx + ww - width - PADDING;
        let y = wy + PADDING;
        let available = wh - PADDING * 2.0;

        // Rows are added while they fit; the rest are counted, so a short window says so instead of
        // drawing over the inventory.
        let mut rows = Vec::with_capacity(Setting::ALL.len());
        let mut omitted = 0;
        let first_y = y + PADDING + TITLE_HEIGHT + 2.0;
        for (index, setting) in Setting::ALL.iter().copied().enumerate() {
            let row_y = first_y + index as f32 * (ROW_HEIGHT + ROW_GAP);
            if row_y + ROW_HEIGHT > y + available {
                omitted += 1;
                continue;
            }
            rows.push(row(settings, setting, x, y, width, row_y));
        }

        let height = (PADDING * 2.0 + TITLE_HEIGHT + 2.0
            + rows.len() as f32 * (ROW_HEIGHT + ROW_GAP))
            .min(available);
        Panel {
            rect: [x, y, width, height],
            title: TITLE.to_string(),
            rows,
            omitted,
        }
    }

    /// Where the title's text starts.
    pub fn title_at(&self) -> [f32; 2] {
        [self.rect[0] + PADDING, self.rect[1] + PADDING]
    }

    /// Whether `at` is anywhere on the panel — the test the mouse hooks use to decide whether a
    /// click belongs to the panel instead of the inventory underneath it.
    pub fn contains(&self, at: [f32; 2]) -> bool {
        let [x, y, w, h] = self.rect;
        at[0] >= x && at[0] < x + w && at[1] >= y && at[1] < y + h
    }

    /// The row for a setting, if it is shown.
    pub fn row(&self, setting: Setting) -> Option<&PanelRow> {
        self.rows.iter().find(|row| row.setting == setting)
    }

    /// The row whose control is under `at`.
    pub fn row_at(&self, at: [f32; 2]) -> Option<&PanelRow> {
        self.rows.iter().find(|row| row.contains(at))
    }
}

fn row(
    settings: &BuilderSettings,
    setting: Setting,
    panel_x: f32,
    panel_y: f32,
    panel_width: f32,
    row_y: f32,
) -> PanelRow {
    let kind = if setting.is_toggle() || setting.is_choice() {
        RowKind::Button
    } else {
        RowKind::Field
    };
    let control_width = match kind {
        RowKind::Field => FIELD_WIDTH,
        RowKind::Button => BUTTON_WIDTH,
    };
    let value = settings.value(setting);
    let _ = panel_y;
    PanelRow {
        setting,
        label: setting.label().to_string(),
        hint: hint(setting),
        kind,
        rect: [
            panel_x + panel_width - PADDING - control_width,
            row_y,
            control_width,
            ROW_HEIGHT,
        ],
        label_at: [panel_x + PADDING, row_y + 6.0],
        value: caption(setting, &value),
    }
}

/// The range or the accepted words, as the field's suggestion.
fn hint(setting: Setting) -> String {
    match setting.range() {
        Some((min, max)) => format!("{}-{}", trim(min), trim(max)),
        None if setting.is_toggle() => "on/off".to_string(),
        None => "iron/copper".to_string(),
    }
}

/// What a row's control shows: a field shows the bare value, a button names what it acts on.
fn caption(setting: Setting, value: &str) -> String {
    match setting {
        Setting::GolemFetching => format!("Fetching: {value}"),
        Setting::GolemKind => format!("Golem: {value}"),
        _ => value.to_string(),
    }
}

fn trim(value: f64) -> String {
    let text = format!("{value:.2}");
    let text = text.trim_end_matches('0').trim_end_matches('.');
    if text.is_empty() { "0".to_string() } else { text.to_string() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::settings::Setting;

    const WINDOW: [f32; 4] = [0.0, 0.0, 854.0, 480.0];

    #[test]
    fn the_panel_sits_on_the_right_edge_of_the_window() {
        let panel = Panel::layout(WINDOW, &BuilderSettings::DEFAULT);
        let [x, y, w, h] = panel.rect;
        assert!((x + w + PADDING - WINDOW[2]).abs() < 1.0e-4, "flush to the right edge");
        assert_eq!(y, PADDING);
        assert!((w - (854.0 * WIDTH_FRACTION)).abs() < 1.0e-4);
        assert!(h > 0.0);
        assert_eq!(panel.title, TITLE);

        // The inventory's 195 pixels stay clear of it.
        assert!(x > 195.0, "panel starts at {x}, past the vanilla inventory");
    }

    #[test]
    fn a_click_inside_the_panel_is_the_panels_and_one_on_the_inventory_is_not() {
        let panel = Panel::layout(WINDOW, &BuilderSettings::DEFAULT);
        let [x, y, w, h] = panel.rect;
        assert!(panel.contains([x + 1.0, y + 1.0]));
        assert!(panel.contains([x + w - 1.0, y + h - 1.0]));
        assert!(!panel.contains([x - 1.0, y + 1.0]));
        assert!(!panel.contains([x + w, y]));
        assert!(!panel.contains([100.0, 200.0]), "over the item list");
    }

    #[test]
    fn every_setting_gets_a_row_with_the_kind_its_value_needs() {
        let settings = BuilderSettings::DEFAULT;
        let panel = Panel::layout(WINDOW, &settings);
        assert_eq!(panel.omitted, 0, "a 480-pixel window fits all ten rows");
        assert_eq!(panel.rows.len(), Setting::ALL.len());

        for setting in Setting::ALL {
            let row = panel.row(setting).unwrap_or_else(|| panic!("{setting:?} has no row"));
            assert_eq!(row.label, setting.label());
            match row.kind {
                RowKind::Field => {
                    assert_eq!(row.value, settings.value(setting));
                    assert_eq!(row.rect[2], FIELD_WIDTH);
                    assert!(row.hint.contains('-') || row.hint == "on/off");
                }
                RowKind::Button => {
                    assert!(setting.is_toggle() || setting.is_choice());
                    assert_eq!(row.rect[2], BUTTON_WIDTH);
                }
            }
            // Controls line up on the panel's right edge, labels on its left.
            let right = row.rect[0] + row.rect[2];
            assert!((right - (panel.rect[0] + panel.rect[2] - PADDING)).abs() < 1.0e-4);
            assert_eq!(row.label_at[0], panel.rect[0] + PADDING);
        }
    }

    #[test]
    fn a_button_row_names_what_it_does_and_a_field_shows_its_value() {
        let mut settings = BuilderSettings::DEFAULT;
        settings.golem_fetching = false;
        let panel = Panel::layout(WINDOW, &settings);
        assert_eq!(panel.row(Setting::GolemFetching).unwrap().value, "Fetching: off");
        assert_eq!(panel.row(Setting::GolemKind).unwrap().value, "Golem: iron");
        assert_eq!(panel.row(Setting::WallHeight).unwrap().value, "4");
        assert_eq!(panel.row(Setting::WallThickness).unwrap().value, "1");
        assert_eq!(panel.row(Setting::WallHeight).unwrap().hint, "1-64");
    }

    #[test]
    fn a_row_is_hit_only_on_its_control() {
        let panel = Panel::layout(WINDOW, &BuilderSettings::DEFAULT);
        let row = panel.row(Setting::WallHeight).unwrap();
        let [x, y, _w, h] = row.rect;
        assert_eq!(panel.row_at([x + 1.0, y + 1.0]).map(|r| r.setting), Some(Setting::WallHeight));
        assert!(panel.row_at([x - 40.0, y + 1.0]).is_none(), "the label is not the control");
        assert!(panel.row_at([x + 1.0, y + h]).is_none(), "just below the row");
    }

    #[test]
    fn a_short_window_omits_rows_and_says_how_many() {
        let small = [0.0, 0.0, 640.0, 120.0];
        let panel = Panel::layout(small, &BuilderSettings::DEFAULT);
        assert!(panel.omitted > 0, "ten rows cannot fit in 120 pixels");
        assert_eq!(panel.rows.len() + panel.omitted, Setting::ALL.len());
        // What is shown stays inside the panel.
        let bottom = panel.rect[1] + panel.rect[3];
        for row in &panel.rows {
            assert!(row.rect[1] + row.rect[3] <= bottom + 1.0e-4);
        }
    }

    #[test]
    fn the_panel_never_leaves_the_window() {
        for width in [320.0_f32, 854.0, 1920.0] {
            let window = [0.0, 0.0, width, 480.0];
            let panel = Panel::layout(window, &BuilderSettings::DEFAULT);
            assert!(panel.rect[2] >= MIN_PANEL_WIDTH);
            assert!(panel.rect[2] <= MAX_PANEL_WIDTH);
            assert!(panel.rect[0] >= 0.0, "left edge at {} for width {width}", panel.rect[0]);
        }
    }

    #[test]
    fn editing_a_value_moves_from_the_settings_to_the_row() {
        let mut settings = BuilderSettings::DEFAULT;
        settings.set_text(Setting::WallHeight, "9").unwrap();
        settings.press(Setting::GolemFetching).unwrap();
        let panel = Panel::layout(WINDOW, &settings);
        assert_eq!(panel.row(Setting::WallHeight).unwrap().value, "9");
        assert_eq!(panel.row(Setting::GolemFetching).unwrap().value, "Fetching: off");
    }
}
