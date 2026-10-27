/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import java.util.Locale;
import java.util.WeakHashMap;
import javafx.scene.Group;
import javafx.geometry.Dimension2D;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Screen-aware sizing shared by all desktop windows. */
final class UiScale {

    private static final double BASE_SCREEN_WIDTH = 1440;
    private static final double BASE_SCREEN_HEIGHT = 900;
    private static final double BASE_FONT_SIZE = 13;
    private static final WeakHashMap<Stage, Boolean> WATCHED_STAGES = new WeakHashMap<>();
    private static final WeakHashMap<Stage, MinimumSize> MINIMUM_SIZES = new WeakHashMap<>();
    private static volatile double currentScale = 1;

    private UiScale() { }

    static double scale(Window window) {
        Rectangle2D bounds = visualBounds(window);
        double areaScale = Math.sqrt((bounds.getWidth() * bounds.getHeight())
                / (BASE_SCREEN_WIDTH * BASE_SCREEN_HEIGHT));
        return clamp(areaScale, 0.85, 1.35);
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
        if (WATCHED_STAGES.putIfAbsent(stage, Boolean.TRUE) == null) {
            stage.xProperty().addListener((observable, oldValue, newValue) -> applyCurrentScene(stage));
            stage.yProperty().addListener((observable, oldValue, newValue) -> applyCurrentScene(stage));
        }
    }

    private static void applyCurrentScene(Stage stage) {
        updateMinimumSize(stage, stage);
        Rectangle2D bounds = visualBounds(stage);
        double maxWidth = bounds.getWidth() * 0.98;
        double maxHeight = bounds.getHeight() * 0.96;
        if (stage.getWidth() > maxWidth) stage.setWidth(maxWidth);
        if (stage.getHeight() > maxHeight) stage.setHeight(maxHeight);
        applyFontScale(stage.getScene() == null ? null : stage.getScene().getRoot(), stage);
    }

    private static void updateMinimumSize(Stage stage, Window anchor) {
        MinimumSize minimum = MINIMUM_SIZES.get(stage);
        if (minimum == null) return;
        Rectangle2D bounds = visualBounds(anchor);
        double scale = scale(anchor);
        stage.setMinWidth(Math.min(bounds.getWidth() * 0.98, minimum.width() * scale));
        stage.setMinHeight(Math.min(bounds.getHeight() * 0.96, minimum.height() * scale));
    }

    private static void applyFontScale(Node root, Window window) {
        if (root == null) return;
        String existing = root.getStyle();
        String style = existing == null ? "" : existing.trim();
        style = style.replaceAll("(?i)(^|;)\\s*-fx-font-size\\s*:[^;]*;?", "$1").trim();
        if (!style.isEmpty() && !style.endsWith(";")) style += ";";
        double fontSize = BASE_FONT_SIZE * scale(window);
        currentScale = scale(window);
        root.setStyle(style + String.format(Locale.ROOT, " -fx-font-size: %.2fpx;", fontSize));
        if (root.getScene() != null) {
            root.applyCss();
            for (Node icon : root.lookupAll(".thunderbird-icon")) {
                if (icon instanceof Group group) {
                    Object baseScale = group.getProperties().get(ThunderbirdIcons.BASE_SCALE_PROPERTY);
                    if (baseScale instanceof Number base) {
                        double iconScale = base.doubleValue() * currentScale;
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
