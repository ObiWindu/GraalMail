/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.graalmail.android.work.SyncScheduler;

/**
 * The application class of the Android client.
 *
 * <p>It opens the local database, prepares the credential store and schedules
 * the background synchronisation.</p>
 */
public final class GraalMailApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppContext context = AppContext.of(this);
        String theme = context.store().setting("android.theme", "system");
        AppCompatDelegate.setDefaultNightMode("light".equals(theme)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : "dark".equals(theme) ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        SyncScheduler.schedule(this, context);
    }
}
