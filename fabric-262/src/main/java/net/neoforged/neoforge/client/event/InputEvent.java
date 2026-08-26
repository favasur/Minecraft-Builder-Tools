package net.neoforged.neoforge.client.event;

public final class InputEvent {
    private InputEvent() {
    }

    public abstract static class Cancellable {
        private boolean canceled;

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }

        public boolean isCanceled() {
            return canceled;
        }
    }

    public static final class MouseButton {
        private MouseButton() {
        }

        public static final class Pre extends Cancellable {
            private final int button;
            private final int action;

            public Pre(int button, int action) {
                this.button = button;
                this.action = action;
            }

            public int getButton() {
                return button;
            }

            public int getAction() {
                return action;
            }
        }

        public static final class Post extends Cancellable {
            private final int button;
            private final int action;

            public Post(int button, int action) {
                this.button = button;
                this.action = action;
            }

            public int getButton() {
                return button;
            }

            public int getAction() {
                return action;
            }
        }
    }

    public static final class MouseScrollingEvent extends Cancellable {
        private final double scrollDeltaY;

        public MouseScrollingEvent(double scrollDeltaY) {
            this.scrollDeltaY = scrollDeltaY;
        }

        public double getScrollDeltaY() {
            return scrollDeltaY;
        }
    }
}
