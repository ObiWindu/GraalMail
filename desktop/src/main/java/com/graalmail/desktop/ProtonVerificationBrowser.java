/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;

/**
 * Opens Proton's login page in a private, managed Firefox window for
 * human-verification checks.
 *
 * <p>Why this shape: the embedded JavaFX WebView is rejected by Proton as an
 * unsupported browser, system browser plumbing ({@code xdg-open}) is broken
 * on some Linux desktops, and no library renders a Firefox engine inside a
 * JavaFX window (GeckoView is Android-only, XULRunner embedding is long
 * dead). Playwright driving its Firefox build is the only shippable way to
 * put a real Gecko engine in front of the user here.</p>
 *
 * <p>Cost, stated plainly: Firefox (~90&nbsp;MB) is downloaded once into
 * the user's Playwright cache when it is missing. Callers must get explicit
 * user consent first and run {@link #open} off the UI thread. Nothing is
 * read out of the page; the user completes the check there, then retries
 * in GraalMail with the credentials from the native fields.</p>
 */
final class ProtonVerificationBrowser implements AutoCloseable {

    private static final Logger LOG = Log.of(ProtonVerificationBrowser.class);

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private java.util.function.Consumer<Browser> disconnectHandler;

    /**
     * Registers a handler for the browser window closing, e.g. to retry the
     * sign-in automatically. Must be set before {@link #open}. The handler
     * runs on a Playwright thread, not the JavaFX thread.
     */
    synchronized void setDisconnectHandler(java.util.function.Consumer<Browser> handler) {
        disconnectHandler = handler;
    }

    /**
     * Opens the URL in a managed Firefox window, closing any previous one.
     *
     * @param url the page to open, normally Proton's login page
     * @throws IllegalStateException when no window can be opened
     */
    synchronized void open(String url) {
        close();
        try {
            playwright = Playwright.create();
            browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false));
        } catch (RuntimeException e) {
            close();
            throw new IllegalStateException(
                    "Could not open a verification window. Check the connection and disk space, "
                            + "or use the system browser button instead.", e);
        }
        if (disconnectHandler != null) {
            browser.onDisconnected(disconnectHandler);
        }
        try {
            context = browser.newContext();
            page = context.newPage();
            page.navigate(url);
        } catch (RuntimeException e) {
            close();
            throw new IllegalStateException("The verification page could not be loaded.", e);
        }
    }

    /** Closes the managed window, if any. Safe to call repeatedly. */
    @Override
    public synchronized void close() {
        if (page != null) {
            try {
                page.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
            page = null;
        }
        if (context != null) {
            try {
                context.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
            context = null;
        }
        if (browser != null) {
            try {
                browser.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
            browser = null;
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
            playwright = null;
        }
    }
}
