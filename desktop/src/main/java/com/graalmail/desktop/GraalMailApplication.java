/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * The JavaFX application of the desktop client.
 *
 * <p>The application is launched by {@link GraalMailApp}, which does not extend
 * {@link Application}. The indirection is required because the Java launcher
 * rejects a main class that extends {@link Application} when JavaFX is loaded
 * from the class path instead of the module path. The class path form is used
 * by the distribution created with Gradle and by the jpackage image.</p>
 */
public final class GraalMailApplication extends Application {

    private DesktopContext context;
    private MainWindow window;

    @Override
    public void start(Stage stage) throws Exception {
        AppPaths paths = AppPaths.userDefault();
        context = DesktopContext.open(paths);
        window = new MainWindow(context);
        stage.setTitle("GraalMail");
        Scene scene = window.build(stage);
        stage.setScene(scene);
        UiScale.configure(stage, scene, stage, 1520, 980, 700, 480);
        stage.setOnCloseRequest(event -> context.close());
        stage.show();
    }

    @Override
    public void stop() {
        if (window != null) {
            window.close();
            window = null;
        }
        if (context != null) {
            context.close();
            context = null;
        }
    }
}
