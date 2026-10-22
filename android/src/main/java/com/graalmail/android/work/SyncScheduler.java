/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.work;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.graalmail.android.AppContext;

import java.util.concurrent.TimeUnit;

/**
 * Schedules the background synchronisation of the Android client.
 *
 * <p>The synchronisation only runs when a network connection is available, so
 * the client does not waste battery on a failing connection.</p>
 */
public final class SyncScheduler {

    /** The unique name of the periodic work. */
    public static final String WORK_NAME = "graalmail-sync";
    /** The shortest interval that the platform allows. */
    public static final long MINIMUM_INTERVAL_MINUTES = 15;

    private SyncScheduler() {
    }

    private static long configuredInterval(AppContext context) {
        String raw = context.store().setting("android.syncIntervalMinutes", "15");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return MINIMUM_INTERVAL_MINUTES;
        }
    }

    /**
     * Schedules the periodic synchronisation, replacing an existing schedule.
     *
     * @param context the application context
     * @param appContext the GraalMail context
     */
    public static void schedule(Context context, AppContext appContext) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        long interval = Math.max(MINIMUM_INTERVAL_MINUTES, configuredInterval(appContext));
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SyncWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
