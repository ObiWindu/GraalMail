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

import java.util.concurrent.TimeUnit;

/**
 * Schedules PolyMail background reconciliation (spec section 16.1).
 *
 * <p>Runs only when connected and the battery is not low, survives process
 * termination via the durable scan cursor, and never assumes a permanently
 * running service.
 */
public final class PolyMailScheduler {

    /** The unique name of the periodic work. */
    public static final String WORK_NAME = "polymail-scan";

    private PolyMailScheduler() {
    }

    /**
     * Schedules periodic reconciliation, replacing an existing schedule.
     *
     * @param context the application context
     * @param intervalMinutes the scan interval, at least 15
     */
    public static void schedule(Context context, long intervalMinutes) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        long interval = Math.max(15, intervalMinutes);
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PolyMailWorker.class, interval, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    /** Cancels background reconciliation, e.g. when PolyMail is disabled. */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
