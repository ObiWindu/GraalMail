/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.notify;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.graalmail.android.R;
import com.graalmail.android.ui.MailboxListActivity;

/**
 * Shows the notifications for newly synchronized messages.
 *
 * <p>The notification only carries the number of new messages, never the
 * sender or the subject, so that the lock screen does not leak the content of
 * the mailbox.</p>
 */
public final class NotificationHelper {

    /** The identifier of the notification channel for new mail. */
    public static final String CHANNEL_ID = "graalmail.new_mail";

    private NotificationHelper() {
    }

    /**
     * Creates the notification channel. The call is ignored on older systems.
     *
     * @param context the application context
     */
    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel_new_mail),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_new_mail_description));
        manager.createNotificationChannel(channel);
    }

    /**
     * Shows a notification about new messages.
     *
     * @param context the application context
     * @param count the number of new messages
     */
    public static void notifyNewMail(Context context, int count) {
        if (count <= 0) {
            return;
        }
        if (!Boolean.parseBoolean(com.graalmail.android.AppContext.of(context).store()
                .setting("android.notifications", "true"))) {
            return;
        }
        createChannel(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(context, MailboxListActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = count == 1
                ? context.getString(R.string.notification_title_new_mail)
                : context.getString(R.string.notification_title_new_mail_count, count);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mail)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notification_channel_new_mail))
                .setAutoCancel(true)
                .setContentIntent(pending);
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        try {
            manager.notify(1, builder.build());
        } catch (SecurityException ignored) {
            // The permission was revoked between the check and the call.
        }
    }
}
