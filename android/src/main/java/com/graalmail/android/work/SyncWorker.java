/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.graalmail.android.AppContext;
import com.graalmail.android.notify.NotificationHelper;
import com.graalmail.sync.SyncReport;

import java.util.List;

/**
 * The background worker that synchronises the accounts of the device.
 */
public final class SyncWorker extends Worker {

    /**
     * Creates the worker.
     *
     * @param context the application context
     * @param parameters the work parameters
     */
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppContext context = AppContext.of(getApplicationContext());
        List<SyncReport> reports = context.syncEngine().syncAll();
        int newMessages = 0;
        for (SyncReport report : reports) {
            newMessages += report.newMessages();
        }
        if (newMessages > 0) {
            NotificationHelper.notifyNewMail(getApplicationContext(), newMessages);
        }
        return Result.success();
    }
}
