package com.fullstacksystems.tvpanel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the connection that Home Assistant's messages arrive on ({@link Messages}) and shows each one as a toast.
 * Nothing opens, so whatever is playing keeps playing; only a message sent with "Pause for messages" on presses
 * pause first, as the remote's pause key would. A TV in standby drops the message, as before.
 *
 * <p>A foreground service, with a quiet notification, so Android doesn't stop it while another app is in front. It
 * starts at boot and after an update ({@link StartReceiver}), and from the app's own screens and the chip row (see
 * {@link #start}); Android restarts it if it's ever stopped. It runs only while the TV is paired.
 */
public final class MessageService extends Service {
    private static final String TAG = "TvPanelMessages";
    private static final String CHANNEL = "connection";
    private static final int NOTE = 1;

    /** Running in this process: {@link #start} has nothing to do. */
    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Closeable> current = new AtomicReference<>();
    private final Object wake = new Object();
    private volatile boolean stopped;
    /** Try again now: the address or token changed, the screen came on. */
    private boolean kicked;
    private Thread worker;

    /** The TV was paired again or signed out: connect again with the new address and token. */
    private final SharedPreferences.OnSharedPreferenceChangeListener configChanged = (prefs, key) -> {
        if ("url".equals(key) || "token".equals(key)) kick();
    };

    /**
     * Back from standby: the old connection may be dead without knowing it yet (it would find out within
     * {@link Messages#SILENT_MS}), so connect again at once.
     */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            kick();
        }
    };

    /**
     * Starts the service if the TV is paired and it isn't running. Android 12 refuses to start a foreground service
     * while the app isn't in front (from the chip row, say); then it waits for the next chance, or the next boot.
     */
    static void start(Context c) {
        if (running || !Config.load(c).complete()) return;
        try {
            c.startForegroundService(new Intent(c, MessageService.class));
        } catch (IllegalStateException | SecurityException e) {
            Log.i(TAG, "not started now: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Home Assistant messages",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Keeps TV Panel connected so Home Assistant's messages show on the TV.");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTE, note());
        Config.prefs(this).registerOnSharedPreferenceChangeListener(configChanged);
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        worker = new Thread(this::loop, "tv-panel-messages");
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService needs its startForeground.
        startForeground(NOTE, note());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopped = true;
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(configChanged);
        unregisterReceiver(screenOn);
        kick();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification note() {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.mdi_home_outline)
                .setContentTitle("TV Panel")
                .setContentText("Connected to Home Assistant for its messages")
                .setOngoing(true)
                .build();
    }

    private void loop() {
        int failures = 0;
        while (!stopped) {
            Config c = Config.load(this);
            if (!c.complete()) {
                Log.i(TAG, "not paired: stopping");
                main.post(this::stopSelf);
                return;
            }
            Messages.Result r = Messages.listen(c.url, c.token, this::show, current);
            if (r.subscribed) failures = 0;
            long wait = Messages.waitMs(r, failures);
            if (!r.subscribed) failures++;
            Log.i(TAG, r.why + (r.subscribed ? " after listening" : "") + ": next try in " + wait / 1000 + " s");
            synchronized (wake) {
                long until = SystemClock.uptimeMillis() + wait;
                while (!stopped && !kicked) {
                    long left = until - SystemClock.uptimeMillis();
                    if (left <= 0) break;
                    try {
                        wake.wait(left);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (kicked) failures = 0;
                kicked = false;
            }
        }
    }

    /** Ends the wait, or the connection, so the loop connects again now (or ends, once stopped). */
    private void kick() {
        synchronized (wake) {
            kicked = true;
            wake.notifyAll();
        }
        Closeable open = current.get();
        if (open != null) {
            // Closing a socket can block briefly; not on the main thread.
            new Thread(() -> {
                try {
                    open.close();
                } catch (IOException ignored) {
                    // closed either way
                }
            }, "tv-panel-messages-close").start();
        }
    }

    /** Called on the connection's thread. */
    private void show(Messages.Message m) {
        main.post(() -> {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) {
                Log.i(TAG, "in standby: message dropped");
                return;
            }
            if (m.pause) pause();
            Toast.makeText(getApplicationContext(), m.text, Toast.LENGTH_LONG).show();
        });
    }

    /** The remote's pause key, sent to whatever is playing. Only for "Pause for messages". */
    private void pause() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
    }
}
