/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.graalmail.android.notify.NotificationHelper;
import com.graalmail.p2p.PolyMailNode;
import com.graalmail.p2p.delivery.ScanReport;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Background PolyMail reconciliation (spec section 16).
 *
 * <p>Android may suspend or terminate the app, so a WebSocket subscription alone
 * is never relied on: every run scans Polygon logs from the last durable cursor
 * and retrieves missed messages. The application registers its configured
 * {@link PolyMailNode} via {@link #registerNode} after the user sets up
 * PolyMail; unconfigured devices finish immediately with success so ordinary
 * mail sync is unaffected.
 */
public final class PolyMailWorker extends Worker {

    private static volatile Supplier<PolyMailNode> nodeSupplier = () -> null;

    public PolyMailWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    /** Called once the user configures a PolyMail identity on this device. */
    public static void registerNode(Supplier<PolyMailNode> supplier) {
        nodeSupplier = supplier;
    }

    @NonNull
    @Override
    public Result doWork() {
        PolyMailNode node;
        try {
            node = nodeSupplier.get();
        } catch (Exception e) {
            return Result.failure();
        }
        if (node == null || !node.isStarted()) {
            return Result.success();
        }
        try {
            ScanReport report = node.scanOnce().toCompletableFuture().get(10, TimeUnit.MINUTES);
            if (report.delivered() > 0) {
                NotificationHelper.notifyNewMail(getApplicationContext(), report.delivered());
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
