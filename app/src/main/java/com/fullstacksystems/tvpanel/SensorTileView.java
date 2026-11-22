package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * One sensor as a tile, on the air quality screen and along the thermostat screen's bottom: its room and name, its
 * value, its band ("● Poor") and where it sits on that band's scale, which way it went in the last hour, and its last
 * 24 hours with the bands' zones behind the line. Laid out for its shape: wide and short is the name and value on the
 * left and the line on the right; otherwise top to bottom, with a scale and the hours on the chart when it's tall.
 * A tile in a band that needs attention (orange and worse: not Good, not Moderate or Fair) is tinted in its colour, so
 * it stands out from across the room.
 *
 * <p>A tile can hold up to three sensors, as the screen's settings join them: side by side, a column each, laid out
 * as one would be in its column. The room is said once, by the first, unless the next is in another.
 */
final class SensorTileView extends SolarViews.Base {
    /** One sensor's part of the tile. */
    private static final class Sensor {
        String room = "", name = "", value = "—", unit = "", trend = "", low = "", high = "";
        AirQuality.Band band;
        AirQuality.Scale scale;
        double position = Double.NaN;
        List<Solar.Stat> rows = new ArrayList<>();
        boolean loaded;
    }

    private final List<Sensor> sensors = new ArrayList<>();
    /** The sensor being drawn, copied out of {@link #sensors}, and its column's width. */
    private String room = "", name = "", value = "—", unit = "", trend = "", low = "", high = "";
    private AirQuality.Band band;
    private AirQuality.Scale scale;
    private double position = Double.NaN;
    private List<Solar.Stat> rows = new ArrayList<>();
    private long from, to;
    private ZoneId zone = ZoneId.systemDefault();
    private boolean loaded;
    private boolean showRoom = true, anyRoom;
    private float colW;
    private int neutral = 0xE6FFFFFF;

    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF zoneRect = new RectF();
    private final Path line = new Path();
    private final Path area = new Path();
    private final DashPathEffect dash;

    SensorTileView(Context c) {
        super(c);
        dash = new DashPathEffect(new float[] {4 * dp, 4 * dp}, 0);
        sensors.add(new Sensor());
    }

    /** How many sensors it holds, side by side. */
    void setCount(int n) {
        while (sensors.size() < Math.max(1, n)) sensors.add(new Sensor());
        while (sensors.size() > Math.max(1, n)) sensors.remove(sensors.size() - 1);
        invalidate();
    }

    /** What it is and its reading now. {@code scale} and {@code band} null without a band. */
    void setLive(String room, String name, String value, String unit, AirQuality.Band band, AirQuality.Scale scale,
            double position) {
        setLive(0, room, name, value, unit, band, scale, position);
    }

    /** The {@code i}th sensor's {@link #setLive}. */
    void setLive(int i, String room, String name, String value, String unit, AirQuality.Band band,
            AirQuality.Scale scale, double position) {
        if (i < 0 || i >= sensors.size()) return;
        Sensor p = sensors.get(i);
        p.room = room;
        p.name = name;
        p.value = value;
        p.unit = unit;
        p.band = band;
        p.scale = scale;
        p.position = position;
        invalidate();
    }

    /** Its last 24 hours, which way it went in the last hour ("▲ 120 in 1 h"), and its low and high ("" without). */
    void setLines(List<Solar.Stat> rows, long from, long to, ZoneId zone, String trend, String low, String high) {
        setLines(0, rows, from, to, zone, trend, low, high);
    }

    /** The {@code i}th sensor's {@link #setLines}. */
    void setLines(int i, List<Solar.Stat> rows, long from, long to, ZoneId zone, String trend, String low,
            String high) {
        if (i < 0 || i >= sensors.size()) return;
        Sensor p = sensors.get(i);
        p.rows = rows == null ? new ArrayList<>() : rows;
        p.trend = trend;
        p.low = low;
        p.high = high;
        p.loaded = true;
        this.from = from;
        this.to = to;
        this.zone = zone;
        invalidate();
    }

    /** Draw {@code p} next. */
    private void use(Sensor p) {
        room = p.room;
        name = p.name;
        value = p.value;
        unit = p.unit;
        band = p.band;
        scale = p.scale;
        position = p.position;
        rows = p.rows;
        trend = p.trend;
        low = p.low;
        high = p.high;
        loaded = p.loaded;
    }

    /** Good, or only Moderate (Fair, Humid): no tint. */
    private static boolean good(AirQuality.Band b) {
        return b == null || b.colour == AirQuality.GOOD || b.colour == AirQuality.MODERATE;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Tinted for the first sensor that needs attention.
        AirQuality.Band worst = null;
        anyRoom = false;
        for (Sensor p : sensors) {
            if (worst == null && !good(p.band)) worst = p.band;
            anyRoom |= !p.room.isEmpty();
        }
        float rad = 16 * dp;
        r.set(dp / 2, dp / 2, w - dp / 2, h - dp / 2);
        fill.setColor(worst == null ? 0x14FFFFFF : (worst.colour & 0x00FFFFFF) | 0x1F000000);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setPathEffect(null);
        stroke.setStrokeWidth(Math.max(1, dp));
        stroke.setColor(worst == null ? 0x1FFFFFFF : (worst.colour & 0x00FFFFFF) | 0x66000000);
        c.drawRoundRect(r, rad, rad, stroke);

        int n = sensors.size();
        float cw = w / n;
        String prevRoom = null;
        for (int i = 0; i < n; i++) {
            use(sensors.get(i));
            showRoom = prevRoom == null || !room.equals(prevRoom);
            prevRoom = room;
            colW = cw;
            c.save();
            c.translate(i * cw, 0);
            c.clipRect(0, 0, cw, h);
            // A column of a shared tile is wide enough beside its chart at a little less than a tile alone.
            if (cw >= (n > 1 ? 2.1f : 2.6f) * h) wide(c, cw, h);
            else tall(c, cw, h);
            c.restore();
            if (i > 0) {
                stroke.setStrokeWidth(Math.max(1, dp));
                stroke.setColor(0x1FFFFFFF);
                c.drawLine(i * cw, h * 0.14f, i * cw, h * 0.86f, stroke);
            }
        }
    }

    // ---- layouts -------------------------------------------------------------------------------------------------

    /**
     * Wide and short: on the left the room and name, the value, and a line of its band, trend and low–high, as much
     * as fits; the line on the right.
     */
    private void wide(Canvas c, float w, float h) {
        // The left a third or so, more on a narrow tile so the name and value fit.
        float pad = Math.min(14 * dp, h * 0.16f), x0 = pad + 2 * dp;
        float col = Math.min(Math.max(w * 0.36f, Math.min(w * 0.5f, 175 * dp)), 250 * dp);
        String title = room.isEmpty() || !showRoom ? name : room + " · " + name;
        float y = pad + sp(11);
        text(c, title, x0, y, col - 10 * dp, 11, SolarViews.SUB, regular, false);
        float big = Math.max(sp(20), Math.min(sp(34), h * 0.34f));
        float base = y + 6 * dp + big * 0.86f;
        valueAt(c, x0, base, big);
        float fx = x0, foot = h - pad;
        if (band != null) {
            pill(c, fx, foot - sp(11) * 0.36f, 11);
            tp.setTypeface(medium);
            tp.setTextSize(sp(11));
            fx += sp(11) * 0.6f + 6 * dp + tp.measureText(band.name) + 12 * dp;
        }
        // The trend and the day's range after the band, as much as fits whole: the range goes first, not cut.
        String range = low.isEmpty() ? "" : "24 h  " + low + "–" + high;
        String rest = trend.isEmpty() ? range : range.isEmpty() ? trend : trend + "    " + range;
        float room = col - 10 * dp - (fx - x0);
        tp.setTypeface(regular);
        tp.setTextSize(sp(10));
        if (tp.measureText(rest) > room) rest = trend.isEmpty() || tp.measureText(trend) > room ? "" : trend;
        text(c, rest, fx, foot, room, 10, SolarViews.SUB, regular, false);
        r.set(x0 + col, pad + 2 * dp, w - pad - 4 * dp, h - pad);
        chart(c, r, false);
    }

    /** Top to bottom: room, name, value and band, the scale, the chart, and the day's low and high. */
    private void tall(Canvas c, float w, float h) {
        float pad = Math.min(18 * dp, Math.max(12 * dp, w * 0.06f)), x0 = pad, x1 = w - pad, y = pad;
        // The room, once for a tile of one room; the line kept in every column so their names line up.
        if (anyRoom) {
            if (showRoom && !room.isEmpty()) {
                text(c, room.toUpperCase(java.util.Locale.US), x0, y + sp(10), x1 - x0, 10, SolarViews.SUB, medium, true);
            }
            y += sp(10) + 5 * dp;
        }
        float nameSp = h > 300 * dp ? 16 : 14;
        text(c, name, x0, y + sp(nameSp) * 0.9f, x1 - x0, nameSp, 0xF2FFFFFF, medium, false);
        y += sp(nameSp) + 4 * dp;

        float big = Math.max(sp(26), Math.min(sp(60), h * 0.2f));
        float base = y + big * 0.9f;
        float vx = valueAt(c, x0, base, big);
        // The band and the trend to the right of the value when they fit, else under it.
        tp.setTypeface(medium);
        tp.setTextSize(sp(13));
        float pw = band == null ? 0 : tp.measureText(band.name) + sp(13) * 0.6f + 6 * dp;
        tp.setTypeface(regular);
        tp.setTextSize(sp(12));
        float tw = trend.isEmpty() ? 0 : tp.measureText(trend);
        boolean beside = vx + 16 * dp + Math.max(pw, tw) <= x1;
        // A small value has room beside it for one line: the band, and the trend goes to the footer.
        boolean trendBelow = beside && band != null && !trend.isEmpty() && big < sp(36);
        if (beside) {
            float mid = base - big * 0.36f;
            if (trendBelow) {
                pill(c, x1 - pw, mid, 13);
            } else if (band != null && !trend.isEmpty()) {
                pill(c, x1 - pw, mid - sp(9), 13);
                trendText(c, x1, mid + sp(12) + 2 * dp);
            } else if (band != null) {
                pill(c, x1 - pw, mid, 13);
            } else if (!trend.isEmpty()) {
                trendText(c, x1, mid + sp(12) * 0.36f);
            }
        }
        y = base + 8 * dp;
        boolean under = !beside && (band != null || !trend.isEmpty());
        if (under) {
            float lb = y + sp(12);
            if (band != null) pill(c, x0, lb - sp(12) * 0.36f, 12);
            if (!trend.isEmpty()) trendText(c, x1, lb);
            y = lb + 8 * dp;
        }
        boolean small = h < 220 * dp;
        if (scale != null && !Double.isNaN(position)) {
            bar(c, x0, y + 2 * dp, x1, 6 * dp);
            y += 6 * dp + (small ? 10 : 14) * dp;
        } else {
            y += 4 * dp;
        }
        float footY = h - pad + 2 * dp;
        if (!low.isEmpty()) {
            String foot = small ? "24 h  " + low + "–" + high : "24 h  low " + low + "  ·  high " + high;
            text(c, foot, x0, footY, (x1 - x0) * (trendBelow ? 0.55f : 1), 10, SolarViews.SUB, regular, false);
        }
        if (trendBelow) trendText(c, x1, footY);
        float bottom = footY - sp(10) - (small ? 8 : 10) * dp;
        if (bottom - y < 20 * dp) return;
        r.set(x0, y, x1, bottom);
        chart(c, r, r.height() >= 120 * dp && r.width() >= 200 * dp);
    }

    // ---- parts ---------------------------------------------------------------------------------------------------

    /** The value, light and large, and its unit small after it. Returns where they end. */
    private float valueAt(Canvas c, float x, float base, float big) {
        tp.setTypeface(light);
        tp.setTextSize(big);
        tp.setColor(0xFFFFFFFF);
        tp.setTextAlign(Paint.Align.LEFT);
        c.drawText(value, x, base, tp);
        float end = x + tp.measureText(value);
        if (!unit.isEmpty() && !"—".equals(value)) {
            tp.setTypeface(regular);
            tp.setTextSize(big * 0.36f);
            tp.setColor(SolarViews.SUB);
            c.drawText(unit, end + 5 * dp, base, tp);
            end += 5 * dp + tp.measureText(unit);
        }
        return end;
    }

    /** The trend, right-aligned at {@code right}: brighter when it's moving. */
    private void trendText(Canvas c, float right, float base) {
        boolean moving = trend.startsWith("▲") || trend.startsWith("▼");
        text(c, trend, right, base, colW / 2f, 12, moving ? 0xD9FFFFFF : SolarViews.SUB, regular, false,
                Paint.Align.RIGHT);
    }

    /** "●  Poor" in its band's colour, centred on {@code mid}. */
    private void pill(Canvas c, float x, float mid, float size) {
        tp.setTypeface(medium);
        tp.setTextSize(sp(size));
        tp.setTextAlign(Paint.Align.LEFT);
        float base = mid + sp(size) * 0.36f;
        fill.setColor(band.colour);
        c.drawCircle(x + sp(size) * 0.3f, mid, sp(size) * 0.3f, fill);
        tp.setColor(band.colour);
        c.drawText(band.name, x + sp(size) * 0.6f + 6 * dp, base, tp);
    }

    /** The band scale: a part per band, dim, the reading's band lit, and a white marker where the reading is. */
    private void bar(Canvas c, float x0, float y, float x1, float hh) {
        int n = scale.bands.length;
        float gap = 2 * dp, part = (x1 - x0 - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            AirQuality.Band b = scale.bands[i];
            fill.setColor(b == band ? b.colour : (b.colour & 0x00FFFFFF) | 0x80000000);
            float a = x0 + i * (part + gap);
            r.set(a, y, a + part, y + hh);
            c.drawRoundRect(r, hh / 2, hh / 2, fill);
        }
        float mx = x0 + (float) position * (x1 - x0), my = y + hh / 2;
        fill.setColor(ThermostatViews.CARD);
        c.drawCircle(mx, my, hh * 0.95f + 2 * dp, fill);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(mx, my, hh * 0.95f, fill);
    }

    /**
     * The last 24 hours in {@code box}: the bands' zones behind, the line in each stretch's band colour (or white), a
     * soft fill under it, and a dot at now. With {@code axes}, a scale on the left, the bands' edges dashed, and the
     * hours along the bottom.
     */
    private void chart(Canvas c, RectF box, boolean axes) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < rows.size(); i++) {
            Solar.Stat s = rows.get(i);
            if (Double.isNaN(s.value) || before(i)) continue;
            lo = Math.min(lo, s.value);
            hi = Math.max(hi, s.value);
        }
        if (lo > hi || to <= from) {
            label(c, loaded ? "No history yet" : "Loading…", box.centerX(), box.centerY() + 4 * dp, 11, SolarViews.SUB,
                    regular, Paint.Align.CENTER);
            return;
        }
        // With a scale: round steps, four or so. Without: a little room around the line. Never below zero for a
        // reading that isn't.
        boolean positive = lo >= 0;
        double step = 0;
        if (axes) {
            step = ThermostatViews.niceStep(Math.max(hi - lo, Math.max(Math.abs(hi) * 0.02, 0.5)), 4);
            lo = Math.floor(lo / step) * step;
            hi = Math.ceil(hi / step) * step;
            if (hi - lo < step * 2) hi = lo + step * 2;
        } else {
            double span = hi - lo, pad = span == 0 ? Math.max(Math.abs(hi) * 0.05, 1) : span * 0.12;
            lo = positive ? Math.max(0, lo - pad) : lo - pad;
            hi += pad;
        }
        float left = box.left, right = box.right, top = box.top, bottom = box.bottom, boxBottom = box.bottom;
        if (axes) {
            text.setTextSize(sp(9));
            text.setTypeface(regular);
            float lw = Math.max(text.measureText(Ha.number(hi)), text.measureText(Ha.number(lo)));
            left += lw + 6 * dp;
            bottom -= sp(9) + 6 * dp;
        }

        // Zones: each band's stretch of the scale, faintly, behind everything; only when the chart crosses into more
        // than one, as one zone behind it all says nothing the colour of the line doesn't.
        int zones = 0;
        if (scale != null) {
            for (int k = 0; k < scale.bands.length; k++) {
                double z0 = k == 0 ? lo : Math.max(lo, scale.edges[k]);
                double z1 = k == scale.bands.length - 1 ? hi : Math.min(hi, scale.edges[k + 1]);
                if (z1 > z0) zones++;
            }
        }
        if (zones > 1) {
            int n = scale.bands.length;
            for (int k = 0; k < n; k++) {
                double z0 = k == 0 ? lo : Math.max(lo, scale.edges[k]), z1 = k == n - 1 ? hi : Math.min(hi, scale.edges[k + 1]);
                if (z1 <= z0) continue;
                fill.setColor((scale.bands[k].colour & 0x00FFFFFF) | 0x0F000000);
                zoneRect.set(left, y(z1, lo, hi, top, bottom), right, y(z0, lo, hi, top, bottom));
                c.drawRect(zoneRect, fill);
            }
        }
        if (axes) {
            stroke.setStrokeWidth(Math.max(1, 0.6f * dp));
            stroke.setPathEffect(null);
            for (double v = lo; v <= hi + step * 1e-6; v += step) {
                float yy = y(v, lo, hi, top, bottom);
                stroke.setColor(0x14FFFFFF);
                c.drawLine(left, yy, right, yy, stroke);
                label(c, Ha.number(v), left - 5 * dp, yy + sp(9) * 0.36f, 9, SolarViews.SUB, regular, Paint.Align.RIGHT);
            }
            if (scale != null) {
                stroke.setPathEffect(dash);
                stroke.setColor(0x33FFFFFF);
                for (int k = 1; k < scale.edges.length - 1; k++) {
                    if (scale.edges[k] <= lo || scale.edges[k] >= hi) continue;
                    float yy = y(scale.edges[k], lo, hi, top, bottom);
                    c.drawLine(left, yy, right, yy, stroke);
                }
                stroke.setPathEffect(null);
            }
            for (long[] m : ThermostatViews.hourMarks(from, to, zone, 6)) {
                float xx = x(m[0], left, right);
                stroke.setColor(0x0FFFFFFF);
                c.drawLine(xx, top, xx, bottom, stroke);
                label(c, ThermostatViews.hourLabel((int) m[1]), xx, boxBottom, 9, SolarViews.SUB, regular,
                        Paint.Align.CENTER);
            }
        }

        // The line, a path per stretch in one colour; and the area under it all.
        boolean pen = false;
        float py = 0;
        int colour = 0;
        line.reset();
        stroke.setStrokeWidth((axes ? 2.2f : 1.8f) * dp);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        List<float[]> pts = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Solar.Stat s = rows.get(i);
            if (before(i)) continue;
            if (Double.isNaN(s.value)) {
                // A gap: finish the line so far, and its fill.
                if (pen) {
                    stroke.setColor(colour);
                    c.drawPath(line, stroke);
                }
                flushArea(c, pts, bottom, colour);
                pen = false;
                continue;
            }
            float xx = x(Math.max(s.start, from), left, right), yy = y(s.value, lo, hi, top, bottom);
            int col = colourOf(s.value);
            if (!pen) {
                line.reset();
                line.moveTo(xx, yy);
                colour = col;
            } else if (col != colour) {
                // A new band: end this stretch where the next begins, then carry on in the new colour.
                line.lineTo(xx, yy);
                stroke.setColor(colour);
                c.drawPath(line, stroke);
                line.reset();
                line.moveTo(xx, yy);
                colour = col;
            } else {
                line.lineTo(xx, yy);
            }
            pts.add(new float[] {xx, yy});
            pen = true;
            py = yy;
        }
        if (pen) {
            // History only has changes: the last value holds until now.
            line.lineTo(right, py);
            pts.add(new float[] {right, py});
            stroke.setColor(colour);
            c.drawPath(line, stroke);
            flushArea(c, pts, bottom, colour);
            fill.setShader(null);
            fill.setColor((colour & 0x00FFFFFF) | 0x40000000);
            c.drawCircle(right, py, 6 * dp, fill);
            fill.setColor(colour);
            c.drawCircle(right, py, 3.2f * dp, fill);
        }
    }

    /** The soft fill under one unbroken run of the line. */
    private void flushArea(Canvas c, List<float[]> pts, float bottom, int colour) {
        if (pts.size() < 2) {
            pts.clear();
            return;
        }
        area.reset();
        area.moveTo(pts.get(0)[0], bottom);
        float top = bottom;
        for (float[] p : pts) {
            area.lineTo(p[0], p[1]);
            top = Math.min(top, p[1]);
        }
        area.lineTo(pts.get(pts.size() - 1)[0], bottom);
        area.close();
        int base = (band != null ? band.colour : colour) & 0x00FFFFFF;
        fill.setShader(new LinearGradient(0, top, 0, bottom, base | 0x24000000, base, Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        fill.setShader(null);
        pts.clear();
    }

    private int colourOf(double v) {
        AirQuality.Band b = scale == null ? null : scale.of(v);
        return b == null ? neutral : b.colour;
    }

    /** A row older than the window that isn't where the window starts (the last one before it). */
    private boolean before(int i) {
        return rows.get(i).start < from && i + 1 < rows.size() && rows.get(i + 1).start <= from;
    }

    private float x(long t, float left, float right) {
        return left + (float) ((Math.max(t, from) - from) / (double) (to - from)) * (right - left);
    }

    private static float y(double v, double lo, double hi, float top, float bottom) {
        return bottom - (float) ((v - lo) / (hi - lo)) * (bottom - top);
    }

    private void text(Canvas c, String s, float x, float base, float width, float size, int col,
            android.graphics.Typeface face, boolean spaced) {
        text(c, s, x, base, width, size, col, face, spaced, Paint.Align.LEFT);
    }

    /** One line, cut with "…" to {@code width}. */
    private void text(Canvas c, String s, float x, float base, float width, float size, int col,
            android.graphics.Typeface face, boolean spaced, Paint.Align align) {
        tp.setTypeface(face);
        tp.setTextSize(sp(size));
        tp.setColor(col);
        tp.setTextAlign(align);
        tp.setLetterSpacing(spaced ? 0.1f : 0);
        CharSequence cut = TextUtils.ellipsize(s, tp, Math.max(0, width), TextUtils.TruncateAt.END);
        c.drawText(cut, 0, cut.length(), x, base, tp);
        tp.setLetterSpacing(0);
    }
}
