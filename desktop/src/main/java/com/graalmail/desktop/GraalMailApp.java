/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;

/**
 * The entry point of the Linux desktop application.
 */
public final class GraalMailApp extends Application {

    private static final Logger LOG = Log.of(GraalMailApp.class);

    private DesktopContext context;

    @Override
    public void start(Stage stage) throws Exception {
        AppPaths paths = AppPaths.userDefault();
        context = DesktopContext.open(paths);
        MainWindow window = new MainWindow(context);
        stage.setTitle("GraalMail");
        stage.setScene(window.build(stage));
        stage.setWidth(1200);
        stage.setHeight(780);
        stage.setOnCloseRequest(event -> context.close());
        stage.show();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * Starts the application.
     *
     * @param args command line arguments, unused
     */
    public static void main(String[] args) {
        LOG.info("Starting GraalMail");
        Application.launch(GraalMailApp.class, args);
    }
}
