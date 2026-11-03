package com.dawai.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * جدولة منبّهات الأدوية عبر AlarmManager.setAlarmClock —
 * أقوى نوع منبّه في أندرويد: لا يؤجله وضع توفير الطاقة، ويعمل والتطبيق مغلق.
 */
final class Reminders {

    /** قناة جديدة (v2) بصوت المنبّه — القنوات القديمة لا يمكن تعديل إعداداتها */
    static final String CHANNEL = "dawai_alarm_v2";
    private static final String OLD_CHANNEL = "dawai_reminders";
    private static final String PREFS = "dawai";
    private static final long SNOOZE_MS = 10 * 60 * 1000L;
    private static final int TEST_CODE = 777001;

    private Reminders() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void createChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { nm.deleteNotificationChannel(OLD_CHANNEL); } catch (Exception ignored) {}
        if (nm.getNotificationChannel(CHANNEL) != null) return;

        NotificationChannel ch = new NotificationChannel(CHANNEL, "مواعيد الدواء", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("تنبيه بصوت واهتزاز عند حلول موعد كل جرعة");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        ch.enableLights(true);
        ch.setBypassDnd(true);
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        ch.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        nm.createNotificationChannel(ch);
    }

    static void saveMeds(Context c, String json) {
        prefs(c).edit().putString("meds", json).apply();
    }

    /** يلغي كل المنبّهات السابقة ثم يجدول الموعد القادم لكل (دواء × وقت). */
    static synchronized void scheduleAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        SharedPreferences p = prefs(c);

        Set<String> old = p.getStringSet("codes", new HashSet<String>());
        for (String code : old) {
            try {
                am.cancel(pending(c, Integer.parseInt(code),
                        new Intent(c, ReminderReceiver.class).setAction(ReminderReceiver.ACTION_FIRE)));
            } catch (Exception ignored) {}
        }

        Set<String> codes = new HashSet<>();
        long next = -1;
        try {
            JSONArray meds = new JSONArray(p.getString("meds", "[]"));
            for (int i = 0; i < meds.length(); i++) {
                JSONObject m = meds.getJSONObject(i);
                JSONArray times = m.optJSONArray("times");
                JSONArray days = m.optJSONArray("days");
                if (times == null) continue;
                boolean[] dayOn = new boolean[7];
                if (days == null || days.length() == 0) {
                    for (int d = 0; d < 7; d++) dayOn[d] = true;
                } else {
                    for (int d = 0; d < days.length(); d++) {
                        int v = days.optInt(d, -1);
                        if (v >= 0 && v < 7) dayOn[v] = true;
                    }
                }
                for (int t = 0; t < times.length(); t++) {
                    String time = times.getString(t);
                    long when = nextTrigger(time, dayOn);
                    if (when <= 0) continue;
                    String medId = m.optString("id");
                    int code = (medId + "|" + time).hashCode() & 0x7fffffff;

                    Intent it = new Intent(c, ReminderReceiver.class)
                            .setAction(ReminderReceiver.ACTION_FIRE)
                            .putExtra("medId", medId)
                            .putExtra("name", m.optString("name"))
                            .putExtra("dose", m.optString("dose"))
                            .putExtra("note", m.optString("note"))
                            .putExtra("time", time);
                    setAlarm(c, am, when, pending(c, code, it));
                    codes.add(String.valueOf(code));
                    if (next < 0 || when < next) next = when;
                }
            }
        } catch (Exception ignored) {}
        p.edit().putStringSet("codes", codes).putLong("next", next).apply();
    }

    /** تأجيل جرعة واحدة ١٠ دقائق */
    static void snooze(Context c, Intent src) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent it = new Intent(src).setClass(c, ReminderReceiver.class).setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra("snoozed", true);
        int code = ("snooze|" + src.getStringExtra("medId") + "|" + src.getStringExtra("time")).hashCode() & 0x7fffffff;
        setAlarm(c, am, System.currentTimeMillis() + SNOOZE_MS, pending(c, code, it));
    }

    /** تنبيه تجريبي */
    static void scheduleTest(Context c, int seconds) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Calendar now = Calendar.getInstance();
        String hhmm = String.format(java.util.Locale.US, "%02d:%02d",
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        Intent it = new Intent(c, ReminderReceiver.class)
                .setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra("medId", "test")
                .putExtra("name", "تنبيه تجريبي")
                .putExtra("dose", "إذا ظهر هذا الإشعار مع الصوت فالتنبيهات تعمل ✓")
                .putExtra("note", "")
                .putExtra("time", hhmm)
                .putExtra("snoozed", true)
                .putExtra("test", true);
        setAlarm(c, am, System.currentTimeMillis() + Math.max(3, seconds) * 1000L, pending(c, TEST_CODE, it));
    }

    static boolean exactAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static void setAlarm(Context c, AlarmManager am, long when, PendingIntent pi) {
        try {
            if (exactAllowed(c)) {
                Intent show = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent showPi = PendingIntent.getActivity(c, 0, show,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    static PendingIntent pending(Context c, int code, Intent it) {
        return PendingIntent.getBroadcast(c, code, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** أقرب موعد قادم (بعد ٣٠ ثانية على الأقل) في يوم مسموح. الأيام: ٠ = الأحد. */
    static long nextTrigger(String hhmm, boolean[] dayOn) {
        String[] parts = hhmm.split(":");
        if (parts.length < 2) return -1;
        int h = Integer.parseInt(parts[0].trim()), min = Integer.parseInt(parts[1].trim());
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, min);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            int wd = cal.get(Calendar.DAY_OF_WEEK) - 1;
            if (dayOn[wd] && cal.getTimeInMillis() > now + 30_000) return cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, min);
        }
        return -1;
    }

    static boolean notificationsOk(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return false;
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
        return ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** تقرير الحالة لشاشة «حالة التنبيهات» */
    static String status(Context c, boolean permission) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
            PowerManager pm = c.getSystemService(PowerManager.class);
            JSONObject o = new JSONObject();
            o.put("permission", permission && nm.areNotificationsEnabled());
            o.put("channel", ch == null || ch.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT);
            o.put("sound", ch == null || ch.getSound() != null);
            o.put("exact", exactAllowed(c));
            o.put("battery", pm.isIgnoringBatteryOptimizations(c.getPackageName()));
            o.put("fullscreen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent());
            o.put("count", prefs(c).getStringSet("codes", new HashSet<String>()).size());
            o.put("next", prefs(c).getLong("next", -1));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("maker", Build.MANUFACTURER);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** حفظ إجراء من الإشعار حتى تسجّله الواجهة عند فتحها */
    static synchronized void addPending(Context c, String key, String status) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("pending", "[]"));
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("status", status);
            o.put("at", System.currentTimeMillis());
            arr.put(o);
            prefs(c).edit().putString("pending", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    static synchronized String takePending(Context c) {
        String v = prefs(c).getString("pending", "[]");
        prefs(c).edit().remove("pending").apply();
        return v;
    }
}
