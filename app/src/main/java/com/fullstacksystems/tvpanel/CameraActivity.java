package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The camera chip's page: the labelled cameras in a 3 by 2 grid, each a snapshot that refreshes about every second
 * while the page is open. OK on a camera opens it full screen and live ({@link CameraViewActivity}). Slots without a
 * camera are drawn empty. Back returns to Home.
 */
public class CameraActivity extends Activity {
    /** How often each tile asks for a new snapshot. A slow answer just makes that tile slower. */
    private static final long SNAP_MS = 1000;
    /** The camera list (names, motion) is read again this often. */
    private static final long LIST_MS = 15_000;
    /** Snapshots are decoded at least this wide; a tile is about 570 px. */
    private static final int DECODE_WIDTH = 640;
    /** A tile says "Offline" after this many failed snapshots in a row. */
    private static final int OFFLINE_AFTER = 3;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService snaps = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable listTick = this::loadList;
    private float dp;
    private TextView clock, message;
    private View grid;
    private final Tile[] tiles = new Tile[Cameras.SLOTS];
    private List<Cameras.Cam> cams;
    /** Snapshot loops of an older generation stop after their current request. */
    private volatile int gen;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        OpenActivity.PAGES.add(this);
        dp = getResources().getDisplayMetrics().density;
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF101826, 0xFF06090F}));
        frame.addView(build());
        message = text("Loading cameras…", 22, 0xCCFFFFFF, false);
        message.setGravity(Gravity.CENTER);
        frame.addView(message, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        loadList();
        if (cams != null) startSnapshots();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        gen++;
        main.removeCallbacks(listTick);
    }

    @Override
    protected void onDestroy() {
        OpenActivity.PAGES.remove(this);
        gen++;
        io.shutdownNow();
        snaps.shutdownNow();
        super.onDestroy();
    }

    // ---- loading -----------------------------------------------------------------------------------------------

    private void loadList() {
        main.removeCallbacks(listTick);
        main.postDelayed(listTick, LIST_MS);
        clock.setText(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)));
        Config c = Config.load(this);
        if (!c.complete()) {
            showMessage(Ha.NOT_PAIRED);
            return;
        }
        io.execute(() -> {
            HaClient.Reply r = Ha.data(new HaClient(c.url, c.token), Ha.DATA_CAMERAS,
                    Cameras.templateBody(SettingsSource.get(c)));
            if (!r.ok()) {
                String why = r.code == 401 ? "Home Assistant refused the token (401)."
                        : r.code == 0 ? "Home Assistant did not answer." : "Home Assistant answered " + r.code + ".";
                post(() -> {
                    if (cams == null) showMessage(why);
                });
                return;
            }
            try {
                List<Cameras.Cam> got = Cameras.parse(r.body);
                post(() -> showList(got));
            } catch (Exception e) {
                post(() -> {
                    if (cams == null) showMessage("Could not read Home Assistant's answer: " + e.getMessage());
                });
            }
        });
    }

    private void showList(List<Cameras.Cam> got) {
        if (got.isEmpty()) {
            showMessage("No cameras on the TV yet. Give camera entities the tv_widget label in Home Assistant.");
            return;
        }
        boolean same = cams != null && ids(cams).equals(ids(got));
        boolean first = cams == null;
        cams = got;
        message.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        for (int i = 0; i < tiles.length; i++) tiles[i].bind(i < got.size() ? got.get(i) : null, i + 1, !same);
        if (first) tiles[0].frame.requestFocus();
        if (!same && resumed) startSnapshots();
    }

    private static List<String> ids(List<Cameras.Cam> list) {
        List<String> out = new ArrayList<>();
        for (Cameras.Cam c : list) out.add(c.id);
        return out;
    }

    private void startSnapshots() {
        int g = ++gen;
        Config c = Config.load(this);
        List<Cameras.Cam> list = cams;
        for (int i = 0; i < list.size() && i < tiles.length; i++) {
            int slot = i;
            String id = list.get(i).id;
            snaps.execute(() -> snapshotLoop(g, slot, id, new HaClient(c.url, c.token)));
        }
    }

    private void snapshotLoop(int g, int slot, String id, HaClient ha) {
        int fails = 0;
        while (g == gen) {
            long t0 = SystemClock.uptimeMillis();
            byte[] b = ha.bytes("/api/camera_proxy/" + id);
            Bitmap bmp = b == null ? null : decode(b);
            fails = bmp == null ? fails + 1 : 0;
            int f = fails;
            post(() -> {
                if (g == gen) tiles[slot].picture(bmp, f >= OFFLINE_AFTER);
            });
            long wait = SNAP_MS - (SystemClock.uptimeMillis() - t0);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** A snapshot shrunk by a power of two to about tile size, so five a second don't load the TV. */
    private static Bitmap decode(byte[] b) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= DECODE_WIDTH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(b, 0, b.length, o);
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    private void showMessage(String s) {
        message.setText(s);
        message.setVisibility(View.VISIBLE);
        grid.setVisibility(View.INVISIBLE);
    }

    private void open(Cameras.Cam cam) {
        startActivity(new Intent(this, CameraViewActivity.class).putExtra(WeatherActivity.EXTRA_KEY, cam.id));
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private View build() {
        LinearLayout root = column();
        root.setPadding(px(36), px(16), px(36), px(14));
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Cameras", 22, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(0, -2, 1f));
        clock = text("", 13, 0x99FFFFFF, false);
        head.addView(clock);
        root.addView(head);

        LinearLayout body = column();
        body.setClipChildren(false);
        root.setClipChildren(false);
        body.setGravity(Gravity.CENTER_VERTICAL);
        grid = body;
        grid.setVisibility(View.INVISIBLE);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (int r = 0; r < Cameras.SLOTS / Cameras.COLUMNS; r++) {
            LinearLayout line = row();
            line.setClipChildren(false);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, -2);
            if (r > 0) lineLp.topMargin = px(18);
            body.addView(line, lineLp);
            for (int c = 0; c < Cameras.COLUMNS; c++) {
                Tile t = new Tile(this);
                tiles[r * Cameras.COLUMNS + c] = t;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (c > 0) lp.leftMargin = px(16);
                line.addView(t.cell, lp);
            }
        }
        return root;
    }

    /** One slot: a 16:9 picture that takes focus, with the camera's name under it. */
    private final class Tile {
        final LinearLayout cell;
        final FrameLayout frame;
        final ImageView picture;
        final TextView note, name, motion;
        final ImageView empty;
        final StateListDrawable ring = new StateListDrawable();
        Cameras.Cam cam;

        Tile(Context ctx) {
            cell = column();
            cell.setClipChildren(false);
            frame = new FrameLayout(ctx) {
                @Override
                protected void onMeasure(int w, int h) {
                    int width = MeasureSpec.getSize(w);
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
                }
            };
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF161E2B);
            bg.setCornerRadius(px(10));
            frame.setBackground(bg);
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(10));
                }
            });
            frame.setClipToOutline(true);
            GradientDrawable on = new GradientDrawable();
            on.setStroke(px(3), 0xFFFFFFFF);
            on.setCornerRadius(px(10));
            ring.addState(new int[] {android.R.attr.state_focused}, on);
            frame.setForeground(ring);

            picture = new ImageView(ctx);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            empty = new ImageView(ctx);
            empty.setImageDrawable(Icons.tinted(ctx, "video_off_outline", 0x40FFFFFF));
            FrameLayout.LayoutParams eLp = new FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER);
            frame.addView(empty, eLp);
            note = text("", 13, 0x99FFFFFF, false);
            note.setGravity(Gravity.CENTER);
            frame.addView(note, new FrameLayout.LayoutParams(-1, -1));
            cell.addView(frame, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout caption = row();
            caption.setGravity(Gravity.CENTER_VERTICAL);
            caption.setPadding(px(2), px(7), px(2), 0);
            name = text("", 15, 0xFFFFFFFF, true);
            caption.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            motion = text("Motion", 11, 0xFF111111, true);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(Ha.ORANGE);
            pill.setCornerRadius(px(9));
            motion.setBackground(pill);
            motion.setPadding(px(8), px(1), px(8), px(2));
            motion.setVisibility(View.GONE);
            caption.addView(motion);
            cell.addView(caption);

            frame.setOnFocusChangeListener((v, has) ->
                    v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start());
            frame.setOnClickListener(v -> {
                if (cam != null) open(cam);
            });
        }

        /** Shows a camera in this slot (or none), keeping its picture when it is still the same camera. */
        void bind(Cameras.Cam c, int number, boolean changed) {
            boolean had = cam != null;
            cam = c;
            if (c == null) {
                name.setText("Camera " + number);
                name.setTextColor(0x55FFFFFF);
                note.setText("");
                picture.setImageDrawable(null);
                empty.setVisibility(View.VISIBLE);
                motion.setVisibility(View.GONE);
                frame.setFocusable(false);
                frame.setForeground(null);
                return;
            }
            name.setText(c.name);
            name.setTextColor(0xFFFFFFFF);
            motion.setVisibility(c.motion ? View.VISIBLE : View.GONE);
            frame.setFocusable(true);
            frame.setForeground(ring);
            if (changed || !had) {
                picture.setImageDrawable(null);
                empty.setVisibility(View.GONE);
                note.setText(c.ok() ? "Loading…" : "Offline");
            }
        }

        void picture(Bitmap b, boolean offline) {
            if (cam == null) return;
            if (b != null) {
                picture.setImageBitmap(b);
                note.setText("");
            } else if (offline) {
                picture.setImageDrawable(null);
                note.setText("Offline");
            }
        }
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

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
