/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import javafx.application.Application;
import org.slf4j.Logger;

/**
 * The entry point of the Linux desktop application.
 *
 * <p>This class intentionally does not extend {@link Application}: the Java
 * launcher only accepts JavaFX class path deployments when the main class is a
 * plain class. See {@link GraalMailApplication} for the application itself.</p>
 */
public final class GraalMailApp {

    private static final Logger LOG = Log.of(GraalMailApp.class);

    private GraalMailApp() {
    }

    /**
     * Starts the application.
     *
     * @param args command line arguments, unused
     */
    public static void main(String[] args) {
        LOG.info("Starting GraalMail");
        Application.launch(GraalMailApplication.class, args);
    }
}
