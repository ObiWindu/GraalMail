/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import org.slf4j.Logger;

/**
 * Shows desktop notifications for new mail.
 *
 * <p>The notifier uses the notification area of the desktop. When the system
 * has no notification area the notifier stays silent instead of failing, so
 * the rest of the application keeps working on a bare window manager.</p>
 */
public final class Notifier {

    private static final Logger LOG = Log.of(Notifier.class);

    private final TrayIcon trayIcon;
    private final boolean supported;

    /**
     * Creates the notifier and registers the icon in the notification area.
     */
    public Notifier() {
        TrayIcon icon = null;
        boolean ok = false;
        try {
            if (SystemTray.isSupported()) {
                SystemTray tray = SystemTray.getSystemTray();
                icon = new TrayIcon(trayIconImage(), "GraalMail");
                icon.setImageAutoSize(true);
                tray.add(icon);
                ok = true;
            }
        } catch (java.awt.AWTException | RuntimeException | Error e) {
            LOG.warn("The notification area is not available: {}", e.getMessage());
        }
        this.trayIcon = icon;
        this.supported = ok;
    }

    /**
     * Whether the desktop can show notifications.
     *
     * @return {@code true} when a notification area is available
     */
    public boolean supported() {
        return supported;
    }

    /**
     * Shows a notification about new mail.
     *
     * @param count the number of new messages
     */
    public void notifyNewMail(int count) {
        if (count <= 0) {
            return;
        }
        String title = count == 1 ? "New message" : count + " new messages";
        notify(title, "GraalMail received " + (count == 1 ? "a new message." : count + " new messages."));
    }

    /**
     * Shows a notification.
     *
     * @param title the title of the notification
     * @param message the text of the notification
     */
    public void notify(String title, String message) {
        if (!supported || trayIcon == null) {
            return;
        }
        try {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        } catch (RuntimeException | Error e) {
            LOG.warn("The notification could not be shown: {}", e.getMessage());
        }
    }

    /**
     * Removes the icon from the notification area.
     */
    public void dispose() {
        if (!supported || trayIcon == null) {
            return;
        }
        try {
            SystemTray.getSystemTray().remove(trayIcon);
        } catch (RuntimeException | Error e) {
            LOG.warn("The notification icon could not be removed: {}", e.getMessage());
        }
    }

    private static java.awt.Image trayIconImage() {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new java.awt.Color(0x1A, 0x73, 0xE8));
            graphics.fillRoundRect(0, 0, size, size, 16, 16);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(14, 22, 36, 24);
            graphics.setColor(new java.awt.Color(0x1A, 0x73, 0xE8));
            graphics.fillPolygon(new int[]{12, 52, 32}, new int[]{22, 22, 40}, 3);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * Loads the AWT toolkit, needed before a tray icon can be created.
     *
     * @return {@code true} when the toolkit is available
     */
    public static boolean toolkitAvailable() {
        try {
            return Toolkit.getDefaultToolkit() != null;
        } catch (RuntimeException | Error e) {
            return false;
        }
    }
}
