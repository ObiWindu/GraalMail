/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.Group;
import javafx.scene.shape.SVGPath;

/** Loads the compact SVG icon set shipped with Thunderbird. */
final class ThunderbirdIcons {

    static final String BASE_SCALE_PROPERTY = "graalmail.base-icon-scale";
    private static final Pattern PATH = Pattern.compile("<path\\b([^>]*)>");
    private static final Pattern DATA = Pattern.compile("\\bd=\"([^\"]+)\"");
    private static final Pattern FILL = Pattern.compile("\\bfill=\"([^\"]+)\"");
    private static final Map<String, List<IconPath>> PATHS = new ConcurrentHashMap<>();

    private ThunderbirdIcons() { }

    static Group icon(String name, String... styleClasses) {
        Group icon = new Group();
        icon.getStyleClass().add("thunderbird-icon");
        icon.getStyleClass().addAll(styleClasses);
        icon.getProperties().put(BASE_SCALE_PROPERTY, 1.0);
        icon.setScaleX(UiScale.currentScale());
        icon.setScaleY(UiScale.currentScale());
        for (IconPath iconPath : PATHS.computeIfAbsent(name, ThunderbirdIcons::read)) {
            SVGPath path = new SVGPath();
            path.setContent(iconPath.data());
            path.getStyleClass().add(iconPath.contextFill() ? "mail-icon-fill" : "mail-icon-stroke");
            icon.getChildren().add(path);
        }
        return icon;
    }

    static Group icon(String name, double scale, String... styleClasses) {
        Group icon = icon(name, styleClasses);
        icon.getProperties().put(BASE_SCALE_PROPERTY, scale);
        double iconScale = scale * UiScale.currentScale();
        icon.setScaleX(iconScale);
        icon.setScaleY(iconScale);
        return icon;
    }

    private static List<IconPath> read(String name) {
        String resource = "/icons/thunderbird/" + name + ".svg";
        try (InputStream stream = ThunderbirdIcons.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing Thunderbird icon: " + resource);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = PATH.matcher(source);
            List<IconPath> paths = new ArrayList<>();
            while (matcher.find()) {
                Matcher data = DATA.matcher(matcher.group(1));
                if (!data.find()) continue;
                Matcher fill = FILL.matcher(matcher.group(1));
                paths.add(new IconPath(data.group(1), fill.find() && fill.group(1).startsWith("context-fill")));
            }
            if (paths.isEmpty()) throw new IllegalStateException("No paths in Thunderbird icon: " + resource);
            return List.copyOf(paths);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load Thunderbird icon " + resource, exception);
        }
    }

    private record IconPath(String data, boolean contextFill) { }
}
