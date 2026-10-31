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

/** Loads the compact SVG icon set shipped with the desktop client (Breeze). */
final class ThunderbirdIcons {

    static final String BASE_SCALE_PROPERTY = "graalmail.base-icon-scale";
    private static final Pattern PATH = Pattern.compile("<path\\b([^>]*)>");
    private static final Pattern DATA = Pattern.compile("\\bd=\"([^\"]+)\"");
    private static final Pattern FILL = Pattern.compile("\\bfill=\"([^\"]+)\"");
    private static final Pattern VIEWBOX = Pattern.compile("viewBox=\"[\\d.]+\\s+[\\d.]+\\s+([\\d.]+)\\s+([\\d.]+)\"");
    private static final Map<String, IconData> ICONS = new ConcurrentHashMap<>();

    private ThunderbirdIcons() { }

    static Group icon(String name, String... styleClasses) {
        return icon(name, 1.0, styleClasses);
    }

    static Group icon(String name, double scale, String... styleClasses) {
        Group icon = new Group();
        icon.getStyleClass().add("thunderbird-icon");
        icon.getStyleClass().addAll(styleClasses);
        IconData data = ICONS.computeIfAbsent(name, ThunderbirdIcons::read);
        double viewBoxScale = data.viewBoxScale();
        icon.getProperties().put(BASE_SCALE_PROPERTY, scale * viewBoxScale);
        double iconScale = scale * viewBoxScale * UiScale.currentScale();
        icon.setScaleX(iconScale);
        icon.setScaleY(iconScale);
        for (IconPath iconPath : data.paths()) {
            SVGPath path = new SVGPath();
            path.setContent(iconPath.data());
            path.getStyleClass().add(iconPath.contextFill() ? "mail-icon-fill" : "mail-icon-stroke");
            icon.getChildren().add(path);
        }
        return icon;
    }

    private static IconData read(String name) {
        String resource = "/icons/thunderbird/" + name + ".svg";
        try (InputStream stream = ThunderbirdIcons.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing icon: " + resource);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher vb = VIEWBOX.matcher(source);
            double vbWidth = 16.0;
            if (vb.find()) {
                try { vbWidth = Double.parseDouble(vb.group(1)); } catch (NumberFormatException ignored) { }
            }
            double viewBoxScale = (vbWidth > 0) ? (16.0 / vbWidth) : 1.0;

            Matcher matcher = PATH.matcher(source);
            List<IconPath> paths = new ArrayList<>();
            while (matcher.find()) {
                Matcher data = DATA.matcher(matcher.group(1));
                if (!data.find()) continue;
                Matcher fill = FILL.matcher(matcher.group(1));
                paths.add(new IconPath(data.group(1), fill.find() && fill.group(1).startsWith("context-fill")));
            }
            if (paths.isEmpty()) throw new IllegalStateException("No paths in icon: " + resource);
            return new IconData(List.copyOf(paths), viewBoxScale);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load icon " + resource, exception);
        }
    }

    private record IconPath(String data, boolean contextFill) { }
    private record IconData(List<IconPath> paths, double viewBoxScale) { }
}