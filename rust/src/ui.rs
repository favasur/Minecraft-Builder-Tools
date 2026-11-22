//! The Creative settings panel on the client: the right-side column, its widgets and its input.
//!
//! The Java screen subclasses `CreativeModeInventoryScreen` and paints a dark-blue panel into the
//! empty right-hand side. Horizon cannot subclass a vanilla screen, and it does not have to: the
//! `screen_*` hooks see the open screen after it drew (`screen_render_post`, with its rect and the
//! mouse) and see every press, key and character before the screen does. So the panel is drawn over
//! the creative inventory and takes exactly the input that lands on it — the inventory's own tabs,
//! search and item grid keep working everywhere else. Nothing is replaced and nothing is reimplemented.
//!
//! Widget state (focus, caret, scroll) lives here rather than in the core, because it is the client's
//! alone; the values live in [`crate::core::settings::BuilderSettings`], which the tools read.

use std::sync::{Mutex, MutexGuard};

use mod_api::client::widgets::{Button, Outcome, TextField, WidgetId, Widgets};
use mod_api::{
    ClientHost, Flow, ScreenCharTypedPreEvent, ScreenKeyPressedPreEvent,
    ScreenMouseButtonPressedPreEvent, ScreenMouseButtonReleasedPreEvent, ScreenMouseDraggedPreEvent,
    ScreenRenderPostEvent, Text,
};

use crate::core::panel::{self, Panel, RowKind};
use crate::core::settings::{BuilderSettings, Setting};

/// The creative inventory's screen id, as [`ScreenView::id`](mod_api::ScreenView) reports it.
const CREATIVE: &str = "creative";
/// How many characters a settings field takes.
const FIELD_LIMIT: usize = 12;

/// The values every tool reads. The panel writes them; nothing else does.
static SETTINGS: Mutex<BuilderSettings> = Mutex::new(BuilderSettings::DEFAULT);

/// The open panel: its layout, its widgets and which widget edits which setting.
static STATE: Mutex<Option<PanelState>> = Mutex::new(None);

fn settings() -> MutexGuard<'static, BuilderSettings> {
    SETTINGS.lock().unwrap_or_else(|error| error.into_inner())
}

fn state() -> MutexGuard<'static, Option<PanelState>> {
    STATE.lock().unwrap_or_else(|error| error.into_inner())
}

/// The settings as they stand — what a tool reads when it runs.
pub fn current_settings() -> BuilderSettings {
    *settings()
}

/// Changes the settings from code (a command, a test hook) as if the panel had been used.
pub fn edit_settings(edit: impl FnOnce(&mut BuilderSettings)) {
    edit(&mut settings());
}

struct PanelState {
    /// The window the widgets were laid out for: a resize rebuilds them.
    window: [f32; 4],
    panel: Panel,
    widgets: Widgets,
    /// Each widget, the setting it edits, and whether it is a field.
    ids: Vec<(WidgetId, Setting, bool)>,
}

impl PanelState {
    fn new(window: [f32; 4], settings: &BuilderSettings) -> Self {
        let panel = Panel::layout(window, settings);
        let mut widgets = Widgets::new();
        let mut ids = Vec::with_capacity(panel.rows.len());
        for row in &panel.rows {
            match row.kind {
                RowKind::Field => {
                    let mut field = TextField::new(row.rect, FIELD_LIMIT);
                    field.set_value(&row.value);
                    field.suggestion = Some(row.hint.clone());
                    field.message = row.label.clone();
                    ids.push((widgets.add_text_field(field), row.setting, true));
                }
                RowKind::Button => {
                    let button = Button::new(row.rect, Text::literal(row.value.clone()));
                    ids.push((widgets.add_button(button), row.setting, false));
                }
            }
        }
        Self {
            window,
            panel,
            widgets,
            ids,
        }
    }

    /// Recomputes the panel and puts the canonical values back into the widgets: a refused typo
    /// reverts, and a clamped step shows the clamp instead of what was asked for.
    fn resync(&mut self, settings: &BuilderSettings) {
        self.panel = Panel::layout(self.window, settings);
        for (id, setting, is_field) in self.ids.clone() {
            let Some(row) = self.panel.row(setting) else {
                continue;
            };
            if is_field {
                if let Some(field) = self.widgets.text_field_mut(id)
                    && field.value() != row.value
                {
                    field.set_value(&row.value);
                }
            } else if let Some(button) = self.widgets.button_mut(id) {
                button.label = Text::literal(row.value.clone());
            }
        }
    }

    /// Applies what a widget did to the settings, then shows the result back.
    fn apply(&mut self, outcome: Outcome) {
        let id = match outcome {
            Outcome::Pressed(id) | Outcome::Submitted(id) | Outcome::Changed(id) => id,
            _ => return,
        };
        let Some(&(_, setting, is_field)) = self.ids.iter().find(|(widget, _, _)| *widget == id)
        else {
            return;
        };
        let mut settings = settings();
        if is_field {
            let text = self
                .widgets
                .text_field(id)
                .map(|field| field.value().to_string())
                .unwrap_or_default();
            let _ = settings.set_text(setting, &text);
        } else {
            let _ = settings.press(setting);
        }
        self.resync(&settings);
    }
}

/// Draws the panel over the creative inventory: `screen_render_post`.
pub fn draw(host: &dyn ClientHost, event: &mut ScreenRenderPostEvent<'_>) {
    let mut guard = state();
    if event.screen.id != CREATIVE {
        // Leaving the creative screen drops the widgets, so the next open starts from the values
        // the tools actually hold.
        *guard = None;
        return;
    }

    let settings = *settings();
    let stale = match guard.as_ref() {
        Some(state) => state.window != event.screen.rect,
        None => true,
    };
    if stale {
        *guard = Some(PanelState::new(event.screen.rect, &settings));
    }
    let Some(state) = guard.as_mut() else {
        return;
    };

    let panel = state.panel.clone();
    event.draw.fill(panel.rect, panel::PANEL_BG);
    event.draw.text(&panel.title, panel.title_at(), panel::TITLE_COLOR, true);
    for row in &panel.rows {
        event.draw.text(&row.label, row.label_at, panel::LABEL_COLOR, false);
    }
    let mouse = event.screen.mouse;
    let _ = state.widgets.draw(event.draw, host, mouse);
}

/// A left press: forwarded to the widgets when it lands on the panel, and swallowed so the item grid
/// behind it does not also take it.
pub fn mouse_pressed(
    host: &dyn ClientHost,
    event: &mut ScreenMouseButtonPressedPreEvent<'_>,
) -> Flow {
    if event.screen.id != CREATIVE {
        return Flow::Continue;
    }
    let mut guard = state();
    let Some(state) = guard.as_mut() else {
        return Flow::Continue;
    };
    if !state.panel.contains(event.screen.mouse) {
        return Flow::Continue;
    }
    let outcome = state
        .widgets
        .mouse_pressed(host, event.screen.mouse, event.button, event.modifiers);
    state.apply(outcome);
    // A press on the panel's background (not on a control) still belongs to the panel.
    Flow::Cancel
}

/// A release or a drag over the panel: end a slider-less drag, extend a text selection.
pub fn mouse_released(event: &ScreenMouseButtonReleasedPreEvent<'_>) -> Flow {
    if !ours(event.screen) {
        return Flow::Continue;
    }
    let mut guard = state();
    let Some(state) = guard.as_mut() else {
        return Flow::Continue;
    };
    if state.widgets.mouse_released(event.button).handled() {
        Flow::Cancel
    } else {
        Flow::Continue
    }
}

pub fn mouse_dragged(
    host: &dyn ClientHost,
    event: &mut ScreenMouseDraggedPreEvent<'_>,
) -> Flow {
    if !ours(event.screen) {
        return Flow::Continue;
    }
    let mut guard = state();
    let Some(state) = guard.as_mut() else {
        return Flow::Continue;
    };
    let outcome = state.widgets.mouse_dragged(host, event.screen.mouse, event.button);
    state.apply(outcome);
    if outcome.handled() {
        Flow::Cancel
    } else {
        Flow::Continue
    }
}

/// A key: Tab moves the focus, Enter presses or submits, the arrows and Backspace edit a field.
/// Keys are only taken while one of the panel's widgets has the focus, so Escape, the inventory key
/// and the creative search keep working.
pub fn key_pressed(host: &dyn ClientHost, event: &mut ScreenKeyPressedPreEvent<'_>) -> Flow {
    if !ours(event.screen) {
        return Flow::Continue;
    }
    let mut guard = state();
    let Some(state) = guard.as_mut() else {
        return Flow::Continue;
    };
    if state.widgets.focused().is_none() {
        return Flow::Continue;
    }
    let outcome = state.widgets.key_pressed(host, event.key, event.modifiers);
    state.apply(outcome);
    if outcome.handled() {
        Flow::Cancel
    } else {
        Flow::Continue
    }
}

/// A typed character goes into the focused field.
pub fn char_typed(host: &dyn ClientHost, event: &mut ScreenCharTypedPreEvent<'_>) -> Flow {
    if !ours(event.screen) {
        return Flow::Continue;
    }
    let mut guard = state();
    let Some(state) = guard.as_mut() else {
        return Flow::Continue;
    };
    if state.widgets.focused().is_none() {
        return Flow::Continue;
    }
    let outcome = state.widgets.char_typed(host, event.c);
    state.apply(outcome);
    if outcome.handled() {
        Flow::Cancel
    } else {
        Flow::Continue
    }
}

/// Whether the open screen is the creative inventory and a panel is on it.
fn ours(screen: &mod_api::ScreenView<'_>) -> bool {
    screen.id == CREATIVE
}
