package com.dawai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** يعيد جدولة المنبّهات بعد إعادة تشغيل الجوال أو تغيير الوقت. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Reminders.scheduleAll(c);
    }
}
