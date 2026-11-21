package com.fullstacksystems.tvpanel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts {@link MessageService} when the TV boots and when this app is updated, two of the few moments Android 12 lets
 * an app start a foreground service from the background.
 */
public final class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            MessageService.start(c);
        }
    }
}
