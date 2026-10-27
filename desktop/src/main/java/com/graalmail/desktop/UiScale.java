/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.geometry.Dimension2D;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Screen- and window-aware sizing shared by all desktop windows.
 *
 * <p>The scale combines three signals:</p>
 * <ul>
 *   <li>screen size: larger screens get a larger base scale,</li>
 *   <li>screen density: high-DPI screens get a modest boost on top,</li>
 *   <li>window size: enlarging a window grows content slightly, shrinking it
 *   compacts content slightly, so the UI keeps fitting.</li>
 * </ul>
 *
 * <p>Most styling lives in CSS {@code em} units, so updating the root
 * {@code -fx-font-size} rescales fonts, paddings, radii and pane minima at
 * once. Java-side pixel math ({@link #px}, {@link #insets}, icons, avatars,
 * the reader zoom) follows through the {@link #onScale} callbacks, which fire
 * debounced whenever the window is resized or moved to another screen.</p>
 */
final class UiScale {

    private static final double BASE_SCREEN_WIDTH = 1440;
    private static final double BASE_SCREEN_HEIGHT = 900;
    private static final double BASE_DPI = 96;
    private static final double BASE_FONT_SIZE = 13;
    private static final double DESIGN_WINDOW_WIDTH = 1520;
    private static final double DESIGN_WINDOW_HEIGHT = 980;
    private static final double MIN_SCALE = 0.8;
    private static final double MAX_SCALE = 1.6;
    private static final double RESCALE_EPSILON = 0.005;

    private static final WeakHashMap<Stage, Boolean> WATCHED_STAGES = new WeakHashMap<>();
    private static final WeakHashMap<Stage, MinimumSize> MINIMUM_SIZES = new WeakHashMap<>();
    private static final WeakHashMap<Stage, Double> APPLIED_SCALES = new WeakHashMap<>();
    private static final WeakHashMap<Stage, List<Runnable>> SCALE_LISTENERS = new WeakHashMap<>();
    private static final WeakHashMap<Stage, PauseTransition> RESCALE_DEBOUNCERS = new WeakHashMap<>();
    private static final WeakHashMap<Stage, Boolean> CLAMP_REQUESTED = new WeakHashMap<>();
    private static volatile double currentScale = 1;

    private UiScale() { }

    /** The combined scale for the screen and window the given window is on. */
    static double scale(Window window) {
        return clamp(screenScale(screenFor(window)) * windowFactor(window), MIN_SCALE, MAX_SCALE);
    }

    static double currentScale() {
        return currentScale;
    }

    static double px(Window window, double value) {
        return value * scale(window);
    }

    static Insets insets(Window window, double top, double right, double bottom, double left) {
        double scale = scale(window);
        return new Insets(top * scale, right * scale, bottom * scale, left * scale);
    }

    static Insets insets(Window window, double all) {
        double value = all * scale(window);
        return new Insets(value);
    }

    static Dimension2D windowSize(Window anchor, double designWidth, double designHeight) {
        Rectangle2D bounds = visualBounds(anchor);
        double scale = scale(anchor);
        return new Dimension2D(
                Math.min(bounds.getWidth() * 0.94, designWidth * scale),
                Math.min(bounds.getHeight() * 0.90, designHeight * scale));
    }

    /**
     * Registers a callback that runs (debounced, on the FX thread) whenever the
     * effective scale of the stage changes through resize or screen moves.
     * Use it to rebuild size-frozen content such as list cells, avatars and
     * web-view zoom levels.
     */
    static synchronized void onScale(Stage stage, Runnable callback) {
        SCALE_LISTENERS.computeIfAbsent(stage, key -> new ArrayList<>()).add(callback);
    }

    static void configure(Stage stage, Scene scene, Window anchor,
                          double designWidth, double designHeight,
                          double minimumWidth, double minimumHeight) {
        boolean needsInitialSize = stage.getWidth() <= 0 || stage.getHeight() <= 0;
        stage.setResizable(true);
        MINIMUM_SIZES.put(stage, new MinimumSize(minimumWidth, minimumHeight));
        updateMinimumSize(stage, anchor);
        if (needsInitialSize) {
            Dimension2D size = windowSize(anchor, designWidth, designHeight);
            stage.setWidth(size.getWidth());
            stage.setHeight(size.getHeight());
        }
        install(scene, stage);
    }

    static void install(Scene scene, Stage stage) {
        applyFontScale(scene == null ? null : scene.getRoot(), stage);
        synchronized (UiScale.class) {
            if (WATCHED_STAGES.putIfAbsent(stage, Boolean.TRUE) != null) return;
            PauseTransition debouncer = new PauseTransition(Duration.millis(120));
            debouncer.setOnFinished(event -> rescale(stage));
            RESCALE_DEBOUNCERS.put(stage, debouncer);
            Runnable scheduleResize = () -> {
                PauseTransition pending;
                synchronized (UiScale.class) {
                    pending = RESCALE_DEBOUNCERS.get(stage);
                }
                if (pending != null) pending.playFromStart();
            };
            Runnable scheduleMove = () -> {
                synchronized (UiScale.class) {
                    CLAMP_REQUESTED.put(stage, Boolean.TRUE);
                }
                scheduleResize.run();
            };
            stage.xProperty().addListener((observable, oldValue, newValue) -> scheduleMove.run());
            stage.yProperty().addListener((observable, oldValue, newValue) -> scheduleMove.run());
            stage.widthProperty().addListener((observable, oldValue, newValue) -> scheduleResize.run());
            stage.heightProperty().addListener((observable, oldValue, newValue) -> scheduleResize.run());
        }
    }

    private static void rescale(Stage stage) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> rescale(stage));
            return;
        }
        boolean clamp;
        synchronized (UiScale.class) {
            clamp = Boolean.TRUE.equals(CLAMP_REQUESTED.remove(stage));
        }
        if (clamp) {
            // Screen moves only: keep the window inside the new screen. Pure
            // resizes are never clamped — clamping those fights the window
            // manager during maximize and causes flicker.
            clampToScreen(stage);
        }
        updateMinimumSize(stage, stage);
        double before;
        synchronized (UiScale.class) {
            before = APPLIED_SCALES.getOrDefault(stage, Double.NaN);
        }
        double now = scale(stage);
        if (!Double.isNaN(before) && Math.abs(now - before) < RESCALE_EPSILON) return;
        applyFontScale(stage.getScene() == null ? null : stage.getScene().getRoot(), stage);
        List<Runnable> listeners;
        synchronized (UiScale.class) {
            listeners = new ArrayList<>(SCALE_LISTENERS.getOrDefault(stage, List.of()));
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A stale listener must not break rescaling of the rest.
            }
        }
    }

    /**
     * Keeps a restored window inside its screen. Maximized and fullscreen
     * windows are owned by the window manager and are never touched: resizing
     * those programmatically un-maximizes them and produces flicker.
     */
    private static void clampToScreen(Stage stage) {
        if (stage.isMaximized() || stage.isFullScreen()) return;
        Rectangle2D bounds = visualBounds(stage);
        double maxWidth = bounds.getWidth() * 0.98;
        double maxHeight = bounds.getHeight() * 0.96;
        if (stage.getWidth() > maxWidth) stage.setWidth(maxWidth);
        if (stage.getHeight() > maxHeight) stage.setHeight(maxHeight);
    }

    private static void updateMinimumSize(Stage stage, Window anchor) {
        MinimumSize minimum = MINIMUM_SIZES.get(stage);
        if (minimum == null) return;
        Rectangle2D bounds = visualBounds(anchor);
        double scale = scale(anchor);
        stage.setMinWidth(Math.min(bounds.getWidth() * 0.98, minimum.width() * scale));
        stage.setMinHeight(Math.min(bounds.getHeight() * 0.96, minimum.height() * scale));
    }

    /** Screen part: area scale with a modest high-density boost. */
    private static double screenScale(Screen screen) {
        Rectangle2D bounds = screen.getVisualBounds();
        double areaScale = Math.sqrt((bounds.getWidth() * bounds.getHeight())
                / (BASE_SCREEN_WIDTH * BASE_SCREEN_HEIGHT));
        areaScale = clamp(areaScale, 0.8, 1.45);
        double dpiFactor = 1.0;
        try {
            double dpi = screen.getDpi();
            if (dpi > 0) dpiFactor = clamp(dpi / BASE_DPI, 1.0, 1.3);
        } catch (RuntimeException ignored) {
            dpiFactor = 1.0;
        }
        return areaScale * dpiFactor;
    }

    /**
     * Window part: grows content slightly on large windows, compacts it on
     * small ones. Gentle (cube-root-ish response) so it assists fitting without
     * fighting the user's resize.
     */
    private static double windowFactor(Window window) {
        double width = -1;
        double height = -1;
        if (window != null) {
            Scene scene = window instanceof Stage stage ? stage.getScene() : null;
            if (scene != null && scene.getWidth() > 0 && scene.getHeight() > 0) {
                width = scene.getWidth();
                height = scene.getHeight();
            } else if (window.getWidth() > 0 && window.getHeight() > 0) {
                width = window.getWidth();
                height = window.getHeight();
            }
        }
        if (width <= 0 || height <= 0) return 1.0;
        double relative = Math.sqrt((width / DESIGN_WINDOW_WIDTH) * (height / DESIGN_WINDOW_HEIGHT));
        return clamp(Math.pow(relative, 0.35), 0.88, 1.12);
    }

    private static void applyFontScale(Node root, Window window) {
        double scale = scale(window);
        currentScale = scale;
        if (window instanceof Stage stage) {
            synchronized (UiScale.class) {
                APPLIED_SCALES.put(stage, scale);
            }
        }
        if (root == null) return;
        String existing = root.getStyle();
        String style = existing == null ? "" : existing.trim();
        style = style.replaceAll("(?i)(^|;)\\s*-fx-font-size\\s*:[^;]*;?", "$1").trim();
        if (!style.isEmpty() && !style.endsWith(";")) style += ";";
        double fontSize = BASE_FONT_SIZE * scale;
        root.setStyle(style + String.format(Locale.ROOT, " -fx-font-size: %.2fpx;", fontSize));
        if (root.getScene() != null) {
            root.applyCss();
            for (Node icon : root.lookupAll(".thunderbird-icon")) {
                if (icon instanceof Group group) {
                    Object baseScale = group.getProperties().get(ThunderbirdIcons.BASE_SCALE_PROPERTY);
                    if (baseScale instanceof Number base) {
                        double iconScale = base.doubleValue() * scale;
                        group.setScaleX(iconScale);
                        group.setScaleY(iconScale);
                    }
                }
            }
        }
    }

    private static Rectangle2D visualBounds(Window window) {
        Screen screen = screenFor(window);
        return screen.getVisualBounds();
    }

    private static Screen screenFor(Window window) {
        if (window != null && window.getWidth() > 0 && window.getHeight() > 0) {
            double centerX = window.getX() + window.getWidth() / 2;
            double centerY = window.getY() + window.getHeight() / 2;
            var screens = Screen.getScreensForRectangle(centerX, centerY, 1, 1);
            if (!screens.isEmpty()) return screens.getFirst();
        }
        return Screen.getPrimary();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record MinimumSize(double width, double height) { }
}
