package com.dawai.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** يستقبل المنبّه ويعرض الإشعار (مع شاشة كاملة فوق القفل)، ويعالج أزرار الإشعار. */
public class ReminderReceiver extends BroadcastReceiver {

    static final String ACTION_FIRE = "com.dawai.app.FIRE";
    static final String ACTION_TAKEN = "com.dawai.app.TAKEN";
    static final String ACTION_SNOOZE = "com.dawai.app.SNOOZE";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        String medId = intent.getStringExtra("medId");
        String time = intent.getStringExtra("time");
        String date = intent.getStringExtra("date");
        int notifId = (medId + "|" + time).hashCode();
        NotificationManager nm = c.getSystemService(NotificationManager.class);

        if (ACTION_TAKEN.equals(action)) {
            nm.cancel(notifId);
            if (!intent.getBooleanExtra("test", false))
                Reminders.addPending(c, medId + "|" + date + "|" + time, "taken");
            return;
        }
        if (ACTION_SNOOZE.equals(action)) {
            nm.cancel(notifId);
            Reminders.snooze(c, intent);
            return;
        }

        // ACTION_FIRE: عرض الإشعار
        Reminders.createChannel(c);
        if (date == null) date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String name = intent.getStringExtra("name");
        String dose = intent.getStringExtra("dose");
        String note = intent.getStringExtra("note");
        String body = (dose == null ? "" : dose) + (note != null && !note.isEmpty() ? " — " + note : "");

        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("fromAlarm", true);
        PendingIntent openPi = PendingIntent.getActivity(c, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent taken = new Intent(intent).setClass(c, ReminderReceiver.class).setAction(ACTION_TAKEN).putExtra("date", date);
        Intent snooze = new Intent(intent).setClass(c, ReminderReceiver.class).setAction(ACTION_SNOOZE).putExtra("date", date);

        Notification.Builder b = new Notification.Builder(c, Reminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(Color.parseColor("#5B8A7E"))
                .setContentTitle("💊 حان موعد الدواء: " + name)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(openPi)
                // يظهر على الشاشة فوراً حتى لو كانت مقفلة
                .setFullScreenIntent(openPi, true)
                .addAction(new Notification.Action.Builder(null, "تناولته ✓",
                        Reminders.pending(c, notifId + 1, taken)).build())
                .addAction(new Notification.Action.Builder(null, "بعد ١٠ دقائق",
                        Reminders.pending(c, notifId + 2, snooze)).build());

        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;   // يكرّر الصوت حتى يتفاعل المستخدم
        try {
            nm.notify(notifId, n);
        } catch (SecurityException ignored) {
            // لم يُمنح إذن الإشعارات — تظهر الحالة في شاشة «حالة التنبيهات»
        }

        // جدولة الموعد التالي لهذا الدواء
        if (!intent.getBooleanExtra("snoozed", false)) Reminders.scheduleAll(c);
    }
}
