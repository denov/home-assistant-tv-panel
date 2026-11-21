package com.fullstacksystems.tvpanel;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app's own page: is Home Assistant reachable, and which chips does it give. Setup lives in Home Assistant. */
public class StatusActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private float dp;
    private PairServer pairing;
    private TextView codeView;
    private String code = "";

    /** The address or token changed (a pushed config.json was taken, or the TV was paired): show it. */
    private final SharedPreferences.OnSharedPreferenceChangeListener configChanged = (prefs, key) -> {
        if (!"url".equals(key) && !"token".equals(key)) return;
        main.post(() -> {
            if (isDestroyed()) return;
            refresh();
            // Just paired: messages can come now.
            MessageService.start(this);
        });
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config.prefs(this).registerOnSharedPreferenceChangeListener(configChanged);
        Config.importPushed(this);
        dp = getResources().getDisplayMetrics().density;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(64), px(48), px(64), px(48));
        root.setBackgroundColor(0xFF0B1220);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pairing = new PairServer(this, new PairServer.Listener() {
            @Override
            public void onCode(String c) {
                main.post(() -> {
                    code = c;
                    if (codeView == null) return;
                    if ("compact".equals(codeView.getTag())) {
                        codeView.setText("Pairing code " + spaced(c) + "  ·  TV address " + PairServer.localAddress());
                    } else {
                        codeView.setText(spaced(c));
                    }
                });
            }

            @Override
            public void onPaired(String url) {
                main.post(() -> { if (!isDestroyed()) refresh(); });
            }
        });
        pairing.start();
        refresh();
        MessageService.start(this);
    }

    @Override
    protected void onPause() {
        if (pairing != null) pairing.stop();
        pairing = null;
        super.onPause();
    }

    /** "482913" reads "482 913" on the TV. */
    static String spaced(String c) {
        return c.length() == 6 ? c.substring(0, 3) + " " + c.substring(3) : c;
    }

    /** How to pair: Home Assistant finds the TV, then asks for this code. Paired, it's one short line at the top. */
    private void pairingHelp(boolean paired) {
        if (paired) {
            codeView = line("Pairing code " + spaced(code) + "  ·  TV address " + PairServer.localAddress(), 16, 0x99FFFFFF);
            codeView.setTag("compact");
            return;
        }
        line(" ", 12, 0);
        line(paired ? "To pair with another Home Assistant, or again: add the TV Panel integration there and enter"
                : "To set up: in Home Assistant, open Settings > Devices & services, add the TV Panel integration,"
                        + " type this TV's address (below) and enter this code:", 18, 0xCCFFFFFF);
        codeView = new TextView(this);
        codeView.setText(spaced(code));
        codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, paired ? 30 : 56);
        codeView.setTextColor(0xFFFFFFFF);
        codeView.setLetterSpacing(0.1f);
        codeView.setPadding(0, px(4), 0, px(4));
        root.addView(codeView);
        line("If Home Assistant asks for the TV's address, it is " + PairServer.localAddress() + ".", 16, 0x99FFFFFF);
    }

    private void refresh() {
        root.removeAllViews();
        codeView = null;
        line("TV Panel", 34, 0xFFFFFFFF);
        line("Checking Home Assistant…", 18, 0x99FFFFFF);
        io.execute(() -> {
            Config c = Config.load(this);
            List<Ha.Widget> got = WidgetProvider.fetch(this);
            String error = WidgetProvider.lastError();
            main.post(() -> {
                if (isDestroyed()) return;
                root.removeAllViews();
                line("TV Panel", 34, 0xFFFFFFFF);
                line(c.complete() ? "Home Assistant: " + c.url : "Not set up yet", 18, 0x99FFFFFF);
                if (!c.complete()) {
                    pairingHelp(false);
                    return;
                }
                pairingHelp(true);
                if (!error.isEmpty()) line(error, 20, 0xFFFF8A80);
                else if (got == null || got.isEmpty()) line("Connected. No widgets yet.", 20, 0xFFFFFFFF);
                else {
                    line("Connected. Widgets on Home:", 20, 0xFFFFFFFF);
                    for (Ha.Widget w : got) chip(w);
                }
                line(" ", 12, 0);
                line("To choose and order the widgets: in Home Assistant, open the TV Panel integration and press Configure.", 18, 0xCCFFFFFF);
                line("To hide the whole row: turn off this TV’s “Widget row” switch in Home Assistant.", 18, 0xCCFFFFFF);
                line("Chips show the entity’s name without its device name; rename the entity in Home Assistant to change it.", 18, 0xCCFFFFFF);
            });
        });
    }

    @Override
    protected void onDestroy() {
        Config.prefs(this).unregisterOnSharedPreferenceChangeListener(configChanged);
        io.shutdownNow();
        super.onDestroy();
    }

    private void chip(Ha.Widget w) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(px(12), px(6), 0, px(6));
        ImageView i = new ImageView(this);
        i.setImageDrawable(Icons.tinted(this, w.icon, w.colour));
        r.addView(i, new LinearLayout.LayoutParams(px(28), px(28)));
        TextView t = new TextView(this);
        t.setText(("   " + w.label + ":  " + w.value + "    (" + w.key + ")").replace(Ha.COLUMN, " / "));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTextColor(0xFFFFFFFF);
        r.addView(t);
        root.addView(r);
    }

    private TextView line(String s, int sp, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setPadding(0, px(6), 0, px(6));
        root.addView(t);
        return t;
    }

    private int px(int v) {
        return Math.round(v * dp);
    }
}
