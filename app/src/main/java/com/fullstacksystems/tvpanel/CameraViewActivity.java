package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One camera, full screen and live: Home Assistant's MJPEG stream (/api/camera_proxy_stream), decoded into one
 * reused bitmap and drawn straight onto a SurfaceView from the reading thread. Left and Right go to the other
 * cameras; the name bar fades after a few seconds and returns on any key. Back returns to the grid (or Home, when
 * opened by voice).
 */
public class CameraViewActivity extends Activity implements SurfaceHolder.Callback {
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long RETRY_MS = 2000;
    private static final long BAR_MS = 5000;
    private static final long LIST_MS = 15_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideBar = () -> this.barView.animate().alpha(0f).setDuration(400).start();
    private final Runnable listTick = this::loadList;
    private float dp;
    private SurfaceView surface;
    private LinearLayout barView;
    private TextView name, motion, clock, note;

    private String current;
    private List<Cameras.Cam> cams;
    /** Bumped to stop the stream that is running. */
    private volatile int gen;
    private volatile boolean surfaceReady;
    private volatile HttpURLConnection conn;
    /** Held while drawing, so the surface is not destroyed under a frame. */
    private final Object surfaceLock = new Object();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        OpenActivity.PAGES.add(this);
        dp = getResources().getDisplayMetrics().density;
        current = getIntent().getStringExtra(WeatherActivity.EXTRA_KEY);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        frame.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        note = text("Connecting…", 20, 0xCCFFFFFF, false);
        note.setGravity(Gravity.CENTER);
        frame.addView(note, new FrameLayout.LayoutParams(-1, -1));

        barView = new LinearLayout(this);
        barView.setOrientation(LinearLayout.HORIZONTAL);
        barView.setGravity(Gravity.CENTER_VERTICAL);
        barView.setPadding(px(36), px(18), px(36), px(36));
        barView.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xB3000000, 0x00000000}));
        TextView live = text("● LIVE", 12, 0xFFFF5A5A, true);
        live.setPadding(0, 0, px(14), 0);
        barView.addView(live);
        name = text("", 22, 0xFFFFFFFF, true);
        barView.addView(name);
        motion = text("Motion", 12, 0xFF111111, true);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Ha.ORANGE);
        pill.setCornerRadius(px(10));
        motion.setBackground(pill);
        motion.setPadding(px(9), px(2), px(9), px(3));
        motion.setVisibility(View.GONE);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-2, -2);
        mLp.leftMargin = px(14);
        barView.addView(motion, mLp);
        barView.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        clock = text("", 14, 0xCCFFFFFF, false);
        barView.addView(clock);
        frame.addView(barView, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showBar();
        loadList();
        startStream();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStream();
        main.removeCallbacks(listTick);
        main.removeCallbacks(hideBar);
    }

    @Override
    protected void onDestroy() {
        OpenActivity.PAGES.remove(this);
        stopStream();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            step(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        if (keyCode != KeyEvent.KEYCODE_BACK) showBar();
        return super.onKeyDown(keyCode, event);
    }

    /** The next or previous camera, round the list. */
    private void step(int by) {
        List<Cameras.Cam> list = cams;
        if (list == null || list.size() < 2) {
            showBar();
            return;
        }
        int at = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(current)) at = i;
        current = list.get(((at + by) % list.size() + list.size()) % list.size()).id;
        showList();
        showBar();
        stopStream();
        clear();
        note.setText("Connecting…");
        note.setVisibility(View.VISIBLE);
        startStream();
    }

    private void showBar() {
        clock.setText(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)));
        barView.animate().cancel();
        barView.setAlpha(1f);
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, BAR_MS);
    }

    // ---- camera list (names, motion, Left/Right) ---------------------------------------------------------------

    private void loadList() {
        main.removeCallbacks(listTick);
        main.postDelayed(listTick, LIST_MS);
        Config c = Config.load(this);
        if (!c.complete()) return;
        io.execute(() -> {
            HaClient.Reply r = new HaClient(c.url, c.token).post("/api/template", Cameras.templateBody(SettingsSource.get(c)));
            if (!r.ok()) return;
            try {
                List<Cameras.Cam> got = Cameras.parse(r.body);
                post(() -> {
                    cams = got;
                    showList();
                });
            } catch (Exception ignored) {
                // keep the name on screen
            }
        });
    }

    private void showList() {
        Cameras.Cam cam = null;
        if (cams != null) for (Cameras.Cam c : cams) if (c.id.equals(current)) cam = c;
        name.setText(cam != null ? cam.name : current == null ? "" : Cameras.name("", current));
        motion.setVisibility(cam != null && cam.motion ? View.VISIBLE : View.GONE);
    }

    // ---- the stream --------------------------------------------------------------------------------------------

    private void startStream() {
        if (current == null) {
            note.setText("No camera named.");
            return;
        }
        int g = ++gen;
        String id = current;
        Config c = Config.load(this);
        if (!c.complete()) {
            note.setText("No Home Assistant address and token yet.");
            return;
        }
        Thread t = new Thread(() -> stream(g, id, new HaClient(c.url, c.token)), "camera-stream");
        t.start();
    }

    private void stopStream() {
        gen++;
        HttpURLConnection c = conn;
        conn = null;
        // Closing the socket ends a read that is waiting; it may touch the network, so not on the main thread.
        if (c != null) new Thread(c::disconnect, "camera-close").start();
    }

    private void stream(int g, String id, HaClient ha) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap reuse = null;
        boolean shown = false;
        while (g == gen) {
            HttpURLConnection c = null;
            try {
                c = ha.stream("/api/camera_proxy_stream/" + id, READ_TIMEOUT_MS);
                conn = c;
                if (g != gen) return;
                Cameras.Mjpeg m = new Cameras.Mjpeg(new BufferedInputStream(c.getInputStream(), 65536));
                while (g == gen && m.next()) {
                    o.inBitmap = reuse;
                    Bitmap b;
                    try {
                        b = BitmapFactory.decodeByteArray(m.buffer(), 0, m.length(), o);
                    } catch (IllegalArgumentException sizeChanged) {
                        o.inBitmap = null;
                        b = BitmapFactory.decodeByteArray(m.buffer(), 0, m.length(), o);
                    }
                    if (b == null) continue;
                    reuse = b;
                    if (g != gen) return;
                    draw(b, paint);
                    if (!shown) {
                        shown = true;
                        post(() -> {
                            if (g == gen) note.setVisibility(View.GONE);
                        });
                    }
                }
            } catch (IOException | RuntimeException e) {
                // dropped: try again below
            } finally {
                if (c != null) c.disconnect();
            }
            if (g != gen) return;
            shown = false;
            post(() -> {
                if (g != gen) return;
                note.setText("Reconnecting…");
                note.setVisibility(View.VISIBLE);
            });
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The frame, fitted to the screen with black bars if its shape differs. Called on the reading thread. */
    private void draw(Bitmap b, Paint paint) {
        synchronized (surfaceLock) {
            drawLocked(b, paint);
        }
    }

    private void drawLocked(Bitmap b, Paint paint) {
        if (!surfaceReady) return;
        SurfaceHolder h = surface.getHolder();
        Canvas c = null;
        try {
            c = h.lockCanvas();
            if (c == null) return;
            float sw = c.getWidth(), sh = c.getHeight();
            float scale = Math.min(sw / b.getWidth(), sh / b.getHeight());
            float w = b.getWidth() * scale, ht = b.getHeight() * scale;
            c.drawColor(Color.BLACK);
            c.drawBitmap(b, null, new RectF((sw - w) / 2, (sh - ht) / 2, (sw + w) / 2, (sh + ht) / 2), paint);
        } catch (RuntimeException e) {
            // the surface went away mid-frame
        } finally {
            if (c != null) {
                try {
                    h.unlockCanvasAndPost(c);
                } catch (RuntimeException ignored) {
                    // released
                }
            }
        }
    }

    /** Black, between cameras, so the old picture doesn't sit under the new name. */
    private void clear() {
        if (!surfaceReady) return;
        io.execute(() -> {
            synchronized (surfaceLock) {
                clearLocked();
            }
        });
    }

    private void clearLocked() {
        if (!surfaceReady) return;
        SurfaceHolder h = surface.getHolder();
        Canvas c = null;
        try {
            c = h.lockCanvas();
            if (c != null) c.drawColor(Color.BLACK);
        } catch (RuntimeException ignored) {
            // gone
        } finally {
            if (c != null) {
                try {
                    h.unlockCanvasAndPost(c);
                } catch (RuntimeException ignored) {
                    // released
                }
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            surfaceReady = false;
        }
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
