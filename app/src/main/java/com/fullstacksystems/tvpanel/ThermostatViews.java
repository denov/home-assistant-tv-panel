package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The thermostat screen's drawn parts: a thermostat's dial and its last 24 hours. */
final class ThermostatViews {
    /** The card behind them, so a knob's ring cuts the track cleanly. */
    static final int CARD = 0xFF212A35;

    private ThermostatViews() {
    }

    /**
     * A thermostat's dial, for the two things to see from across the room: how warm it is and what it's set to, the
     * largest things on it, in the middle. Around them, its range as a 270° arc, where it's set as knobs (orange heat,
     * blue cool), where the room is as a white pointer inside the ring, and, while it heats or cools, the stretch from
     * the room to the setpoint lit in that colour. What it's doing above the temperature; the mode under the setting,
     * when the setting doesn't already say it.
     */
    static final class DialView extends SolarViews.Base {
        private double lo = 50, hi = 90, cur = Double.NaN;
        private double[] targets = {Double.NaN, Double.NaN, Double.NaN};
        private int colour = Thermostat.IDLE;
        private String doing = "", big = "", mode = "", from = "";
        private List<Thermostat.Part> setting = new ArrayList<>();
        private final RectF oval = new RectF();

        DialView(Context c) {
            super(c);
        }

        /**
         * @param scale  the dial's {low, high} ({@link Thermostat#dial})
         * @param cur    the room now, NaN when not known
         * @param targets {heat, cool, other} ({@link Thermostat#targets})
         * @param colour what it's doing, as {@link Thermostat#colour}
         */
        void set(double[] scale, double cur, double[] targets, int colour, String doing, String big,
                List<Thermostat.Part> setting, String mode, String from) {
            lo = scale[0];
            hi = scale[1];
            this.cur = cur;
            this.targets = targets;
            this.colour = colour;
            this.doing = doing;
            this.big = big;
            this.setting = setting;
            this.mode = mode;
            this.from = from;
            invalidate();
        }

        private float angle(double v) {
            double f = Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
            return (float) (135 + 270 * f);
        }

        private float px(float cx, float r, float deg) {
            return cx + r * (float) Math.cos(Math.toRadians(deg));
        }

        private float py(float cy, float r, float deg) {
            return cy + r * (float) Math.sin(Math.toRadians(deg));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || hi <= lo) return;
            float sw = Math.max(7 * dp, Math.min(13 * dp, Math.min(w, h) * 0.05f));
            // The arc's box: a full circle across, and down to 45° below the middle, with the ends' labels under it.
            float knob = sw * 0.85f + 2.5f * dp, endLab = sp(11) + 6 * dp;
            float r = Math.min(w / 2 - knob, (h - knob - sw / 2 - endLab) / 1.7071f);
            if (r < 30 * dp) return;
            float cx = w / 2, used = knob + r * 1.7071f + sw / 2 + endLab;
            float cy = (h - used) / 2 + knob + r;
            oval.set(cx - r, cy - r, cx + r, cy + r);

            // Ticks inside the track: every degree (every 2 over a wide range), longer every 10 (5 in °C ranges).
            double span = hi - lo, step = span > 60 ? 2 : 1, major = span > 30 ? 10 : 5;
            double heat = targets[0], cool = targets[1], other = targets[2];
            stroke.setStrokeCap(Paint.Cap.BUTT);
            stroke.setStrokeWidth(Math.max(1, 1.2f * dp));
            for (double v = Math.ceil(lo / step) * step; v <= hi + 1e-9; v += step) {
                boolean big = Math.abs(v / major - Math.round(v / major)) < 1e-9;
                boolean inside = !Double.isNaN(heat) && !Double.isNaN(cool) && v >= heat && v <= cool;
                float a = angle(v), r0 = r - sw / 2 - Math.min(5 * dp, r * 0.05f);
                float r1 = r0 - Math.min((big ? 9 : 5) * dp, r * (big ? 0.09f : 0.05f));
                stroke.setColor(inside ? 0x80FFFFFF : big ? 0x4DFFFFFF : 0x1FFFFFFF);
                c.drawLine(px(cx, r0, a), py(cy, r0, a), px(cx, r1, a), py(cy, r1, a), stroke);
            }

            // The track, and the set range on it.
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(sw);
            stroke.setColor(0x1FFFFFFF);
            c.drawArc(oval, 135, 270, false, stroke);
            if (!Double.isNaN(heat) && !Double.isNaN(cool) && cool > heat) {
                stroke.setColor(0x40FFFFFF);
                c.drawArc(oval, angle(heat), angle(cool) - angle(heat), false, stroke);
            }

            // What it's doing: from the room to where it's heading, lit, with a glow under it.
            double toward = colour == Thermostat.HEATING ? (Double.isNaN(heat) ? other : heat)
                    : colour == Thermostat.COOLING ? (Double.isNaN(cool) ? other : cool) : Double.NaN;
            if (!Double.isNaN(toward) && !Double.isNaN(cur) && toward != cur) {
                float a0 = angle(Math.min(cur, toward)), a1 = angle(Math.max(cur, toward));
                stroke.setColor((colour & 0x00FFFFFF) | 0x40000000);
                stroke.setStrokeWidth(sw * 2.2f);
                c.drawArc(oval, a0, a1 - a0, false, stroke);
                stroke.setColor(colour);
                stroke.setStrokeWidth(sw);
                c.drawArc(oval, a0, a1 - a0, false, stroke);
            }

            // The ends of the scale.
            label(c, Ha.number(lo) + "°", px(cx, r, 135), py(cy, r, 135) + sw / 2 + sp(11) + 4 * dp, 11, 0x66FFFFFF,
                    regular, Paint.Align.CENTER);
            label(c, Ha.number(hi) + "°", px(cx, r, 45), py(cy, r, 45) + sw / 2 + sp(11) + 4 * dp, 11, 0x66FFFFFF,
                    regular, Paint.Align.CENTER);

            // Where it's set: a knob in its colour on the track.
            knob(c, cx, cy, r, sw, heat, Thermostat.HEATING);
            knob(c, cx, cy, r, sw, cool, Thermostat.COOLING);
            knob(c, cx, cy, r, sw, other, 0xFFFFFFFF);

            // Where the room is: a white pointer inside the ring, clear of the knobs on the track, so it shows even
            // when the room is right at a setpoint (most of the time).
            if (!Double.isNaN(cur)) {
                float a = angle(cur), r1 = r - sw / 2 - 3 * dp, r0 = r1 - Math.min(22 * dp, r * 0.2f);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setStrokeWidth(8 * dp);
                stroke.setColor(CARD);
                c.drawLine(px(cx, r0, a), py(cy, r0, a), px(cx, r1, a), py(cy, r1, a), stroke);
                stroke.setStrokeWidth(4.5f * dp);
                stroke.setColor(0xFFFFFFFF);
                c.drawLine(px(cx, r0, a), py(cy, r0, a), px(cx, r1, a), py(cy, r1, a), stroke);
            }

            // The middle: what it's doing, the room's temperature, the setting in its colours, then the mode and where
            // the room's reading comes from where they fit.
            float bigPx = r * 0.58f;
            text.setTextSize(bigPx);
            text.setTypeface(light);
            float bigW = text.measureText(big);
            if (bigW > r * 1.4f) bigPx *= r * 1.4f / bigW;
            float base = cy + bigPx * 0.2f;
            text.setLetterSpacing(0.14f);
            drawPx(c, doing.toUpperCase(Locale.US), cx, base - bigPx * 0.9f, Math.max(sp(11), r * 0.11f),
                    colour == Thermostat.IDLE ? SolarViews.SUB : colour, medium);
            text.setLetterSpacing(0);
            drawPx(c, big, cx, base, bigPx, 0xFFFFFFFF, light);
            float setPx = Math.max(sp(13), Math.min(sp(26), r * 0.19f));
            float y = base + Math.max(r * 0.27f, setPx * 1.45f);
            if (!setting.isEmpty()) {
                setPx = fitParts(setPx, r * 1.3f);
                drawParts(c, cx, y, setPx);
            }
            // The mode and where the reading's from only inside the ring's opening, clear of the ends' labels.
            float modePx = Math.max(sp(11), Math.min(sp(14), r * 0.1f)), floor = cy + r * 0.72f;
            if (!mode.isEmpty() && fits(mode, modePx, r * 1.2f) && y + modePx * 1.55f <= floor) {
                y += modePx * 1.55f;
                drawPx(c, mode, cx, y, modePx, SolarViews.SUB, regular);
            }
            if (!from.isEmpty() && fits(from, modePx, r * 1.3f) && y + modePx * 1.5f <= floor) {
                drawPx(c, from, cx, y + modePx * 1.5f, modePx, SolarViews.SUB, regular);
            }
        }

        /** The setting's size, smaller until it fits {@code width}. */
        private float fitParts(float sizePx, float width) {
            text.setTypeface(medium);
            text.setTextSize(sizePx);
            float w = 0;
            for (Thermostat.Part p : setting) w += text.measureText(p.text);
            return w > width ? sizePx * width / w : sizePx;
        }

        /** The setting, centred on {@code cx}, each part in its colour. */
        private void drawParts(Canvas c, float cx, float base, float sizePx) {
            text.setTypeface(medium);
            text.setTextSize(sizePx);
            text.setTextAlign(Paint.Align.LEFT);
            float w = 0;
            for (Thermostat.Part p : setting) w += text.measureText(p.text);
            float x = cx - w / 2;
            for (Thermostat.Part p : setting) {
                text.setColor(p.colour);
                c.drawText(p.text, x, base, text);
                x += text.measureText(p.text);
            }
        }

        private boolean fits(String s, float sizePx, float width) {
            text.setTextSize(sizePx);
            text.setTypeface(regular);
            return text.measureText(s) <= width;
        }

        private void knob(Canvas c, float cx, float cy, float r, float sw, double v, int col) {
            if (Double.isNaN(v)) return;
            float a = angle(v), x = px(cx, r, a), y = py(cy, r, a), kr = sw * 0.85f;
            fill.setColor(CARD);
            c.drawCircle(x, y, kr + 2.5f * dp, fill);
            fill.setColor(col);
            c.drawCircle(x, y, kr, fill);
        }

        private void drawPx(Canvas c, String s, float x, float y, float sizePx, int col, android.graphics.Typeface face) {
            text.setTextSize(sizePx);
            text.setColor(col);
            text.setTypeface(face);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(s, x, y, text);
        }
    }

    /** Heating or cooling that stops and starts again within this draws as one block. */
    static final long BLOCK_GAP = 15 * 60_000L;

    /**
     * Where the thermostat was over 24 hours: the room as a white line, where it heated and cooled to as dashed orange
     * and blue lines with the range between them faintly lit, and when it heated or cooled as blocks in that colour
     * behind. Each line's value now at its right end, in its colour (the dial's colours: no legend to look up), and how
     * long it heated and cooled at the top right, in theirs. A temperature scale on the left, the hours along the
     * bottom.
     */
    static final class GraphView extends SolarViews.Base {
        private List<Thermostat.Point> points = new ArrayList<>();
        private List<Thermostat.Span> spans = new ArrayList<>();
        private List<Thermostat.Part> summary = new ArrayList<>();
        private long from, to;
        private ZoneId zone = ZoneId.systemDefault();
        private boolean loaded;
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final DashPathEffect dash;
        /** The window's lowest and highest temperature or setpoint. */
        private double dataLo, dataHi;
        /** The lines' values now, for their labels at the right: value, text, colour; placed in onDraw. */
        private int ends;
        private final double[] endV = new double[4];
        private final String[] endS = new String[4];
        private final int[] endC = new int[4];
        private final float[] endY = new float[4];

        GraphView(Context c) {
            super(c);
            dash = new DashPathEffect(new float[] {7 * dp, 5 * dp}, 0);
        }

        /**
         * @param points  from the one the window starts with ({@link Thermostat#since})
         * @param summary how long it heated and cooled, each in its colour ("Heated 1 h 40 m")
         */
        void set(List<Thermostat.Point> points, long from, long to, ZoneId zone, List<Thermostat.Part> summary) {
            this.points = points;
            this.spans = Thermostat.blocks(Thermostat.spans(points, to), BLOCK_GAP);
            this.summary = summary;
            this.from = from;
            this.to = to;
            this.zone = zone;
            loaded = true;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                if (!counts(i)) continue;
                Thermostat.Point p = points.get(i);
                for (double v : new double[] {p.cur, p.heat, p.cool, p.other}) {
                    if (Double.isNaN(v)) continue;
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
            dataLo = lo;
            dataHi = hi;
            ends = 0;
            if (!points.isEmpty()) {
                Thermostat.Point p = points.get(points.size() - 1);
                end(p.cur, 0xFFFFFFFF);
                end(p.heat, Thermostat.HEATING);
                end(p.cool, Thermostat.COOLING);
                end(p.other, 0xB3FFFFFF);
            }
            invalidate();
        }

        private void end(double v, int colour) {
            if (Double.isNaN(v)) return;
            endV[ends] = v;
            endS[ends] = Thermostat.deg(v);
            endC[ends] = colour;
            ends++;
        }

        private float x(long t, float left, float right) {
            return left + (float) ((Math.max(t, from) - from) / (double) (to - from)) * (right - left);
        }

        /** A point counts if it's in the window, or it's the one the window starts with. */
        private boolean counts(int i) {
            return !(i + 1 < points.size() && points.get(i + 1).t < from);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            text.setLetterSpacing(0.1f);
            label(c, "24 HOURS", 0, sp(12), 11, SolarViews.SUB, medium, Paint.Align.LEFT);
            float titleW = text.measureText("24 HOURS");
            text.setLetterSpacing(0);
            if (loaded) summary(c, summary, w, sp(12), titleW + 16 * dp);

            // Room on the right for the lines' values.
            text.setTextSize(sp(12));
            text.setTypeface(medium);
            float endW = 0;
            for (int i = 0; i < ends; i++) endW = Math.max(endW, text.measureText(endS[i]));
            float left = 38 * dp, right = w - (ends > 0 ? endW + 14 * dp : 8 * dp);
            float top = sp(12) + 16 * dp, bottom = h - sp(11) - 10 * dp;
            double lo = loaded ? dataLo : Double.MAX_VALUE, hi = loaded ? dataHi : -Double.MAX_VALUE;

            if (lo > hi || to <= from) {
                label(c, loaded ? "No history yet" : "Loading…", (left + right) / 2, (top + bottom) / 2 + 4 * dp, 12,
                        SolarViews.SUB, regular, Paint.Align.CENTER);
                hours(c, left, right, top, bottom, h);
                return;
            }

            // The scale: whole steps of 1, 2 or 5 degrees, up to five of them (fewer on a short graph, so the labels
            // don't touch), with a little room around the lines.
            int most = Math.max(2, Math.min(5, (int) ((bottom - top) / (sp(11) * 2f))));
            double step = niceStep(Math.max(hi - lo, 2) * 1.25, most);
            lo = Math.floor((lo - step * 0.4) / step) * step;
            hi = Math.ceil((hi + step * 0.4) / step) * step;
            stroke.setPathEffect(null);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            stroke.setStrokeWidth(Math.max(1, 0.6f * dp));
            for (double v = lo; v <= hi + 1e-9; v += step) {
                float yy = y(v, lo, hi, top, bottom);
                stroke.setColor(0x17FFFFFF);
                c.drawLine(left, yy, right, yy, stroke);
                label(c, Ha.number(v) + "°", left - 6 * dp, yy + sp(11) * 0.36f, 11, SolarViews.SUB, regular,
                        Paint.Align.RIGHT);
            }
            hours(c, left, right, top, bottom, h);

            // When it heated and cooled: a column behind, and a bar along the bottom.
            for (Thermostat.Span s : spans) {
                float x0 = x(s.from, left, right), x1 = x(s.to, left, right);
                if (x1 - x0 < 0.5f) continue;
                x1 = Math.max(x1, x0 + 1.5f * dp);
                fill.setColor((s.colour & 0x00FFFFFF) | 0x2E000000);
                rect.set(x0, top, x1, bottom);
                c.drawRect(rect, fill);
                fill.setColor(s.colour);
                rect.set(x0, bottom - 3 * dp, x1, bottom);
                c.drawRect(rect, fill);
            }

            // The range it was kept in, faintly, between heat-to and cool-to.
            band(c, lo, hi, left, right, top, bottom);

            // Where it heated and cooled to, dashed.
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setPathEffect(dash);
            stroke.setStrokeWidth(1.6f * dp);
            setpoint(c, 0, Thermostat.HEATING, lo, hi, left, right, top, bottom);
            setpoint(c, 1, Thermostat.COOLING, lo, hi, left, right, top, bottom);
            setpoint(c, 2, 0xB3FFFFFF, lo, hi, left, right, top, bottom);
            stroke.setPathEffect(null);

            // The room: straight between readings, the last one held to now.
            path.reset();
            boolean pen = false;
            float lastY = Float.NaN;
            for (int i = 0; i < points.size(); i++) {
                Thermostat.Point p = points.get(i);
                if (Double.isNaN(p.cur)) {
                    if (pen) path.lineTo(x(p.t, left, right), lastY);
                    pen = false;
                    lastY = Float.NaN;
                    continue;
                }
                float xx = x(p.t, left, right), yy = y(p.cur, lo, hi, top, bottom);
                if (!pen) path.moveTo(xx, yy);
                else path.lineTo(xx, yy);
                pen = true;
                lastY = yy;
            }
            if (pen) path.lineTo(right, lastY);
            stroke.setStrokeWidth(2.6f * dp);
            stroke.setColor(0xFFFFFFFF);
            c.drawPath(path, stroke);
            if (pen) {
                fill.setColor(0x40FFFFFF);
                c.drawCircle(right, lastY, 7 * dp, fill);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(right, lastY, 3.6f * dp, fill);
            }
            endLabels(c, lo, hi, right + 10 * dp, top, bottom);
        }

        /**
         * Each line's value now beside its end, in its colour; nudged apart when they're close (the room at a
         * setpoint), the room's first.
         */
        private void endLabels(Canvas c, double lo, double hi, float x, float top, float bottom) {
            float gap = sp(12) + 3 * dp;
            for (int i = 0; i < ends; i++) endY[i] = y(endV[i], lo, hi, top, bottom) + sp(12) * 0.36f;
            // In order down the graph, each at least a line below the one above; then back up if that ran off.
            for (int pass = 0; pass < ends; pass++) {
                for (int i = 0; i < ends; i++) {
                    for (int j = 0; j < ends; j++) {
                        if (i == j) continue;
                        boolean above = endY[i] < endY[j] || (endY[i] == endY[j] && i < j);
                        if (above && endY[j] - endY[i] < gap) endY[j] = endY[i] + gap;
                    }
                }
            }
            float over = 0;
            for (int i = 0; i < ends; i++) over = Math.max(over, endY[i] - bottom - sp(4));
            for (int i = 0; i < ends; i++) {
                label(c, endS[i], x, endY[i] - over, 12, endC[i], i == 0 ? medium : regular, Paint.Align.LEFT);
            }
        }

        /** One kind of setpoint (0 heat, 1 cool, 2 other) as a step line, held until the next change. */
        private void setpoint(Canvas c, int kind, int col, double lo, double hi, float left, float right, float top,
                float bottom) {
            path.reset();
            boolean pen = false;
            for (int i = 0; i < points.size(); i++) {
                Thermostat.Point p = points.get(i);
                double v = kind == 0 ? p.heat : kind == 1 ? p.cool : p.other;
                if (Double.isNaN(v)) {
                    pen = false;
                    continue;
                }
                float x0 = x(p.t, left, right), x1 = x(i + 1 < points.size() ? points.get(i + 1).t : to, left, right);
                float yy = y(v, lo, hi, top, bottom);
                if (!pen) path.moveTo(x0, yy);
                else path.lineTo(x0, yy);
                path.lineTo(x1, yy);
                pen = true;
            }
            stroke.setColor(col);
            c.drawPath(path, stroke);
        }

        /** Between heat-to and cool-to, one shape per stretch with both, so its edges don't leave seams. */
        private void band(Canvas c, double lo, double hi, float left, float right, float top, float bottom) {
            path.reset();
            int start = -1;
            for (int i = 0; i <= points.size(); i++) {
                boolean has = i < points.size() && !Double.isNaN(points.get(i).heat) && !Double.isNaN(points.get(i).cool);
                if (has && start < 0) start = i;
                if (has || start < 0) continue;
                for (int j = start; j < i; j++) {
                    Thermostat.Point p = points.get(j);
                    float x0 = x(p.t, left, right), x1 = x(j + 1 < points.size() ? points.get(j + 1).t : to, left, right);
                    float yy = y(p.cool, lo, hi, top, bottom);
                    if (j == start) path.moveTo(x0, yy);
                    else path.lineTo(x0, yy);
                    path.lineTo(x1, yy);
                }
                for (int j = i - 1; j >= start; j--) {
                    Thermostat.Point p = points.get(j);
                    float x0 = x(p.t, left, right), x1 = x(j + 1 < points.size() ? points.get(j + 1).t : to, left, right);
                    float yy = y(p.heat, lo, hi, top, bottom);
                    path.lineTo(x1, yy);
                    path.lineTo(x0, yy);
                }
                path.close();
                start = -1;
            }
            fill.setColor(0x0FFFFFFF);
            c.drawPath(path, fill);
        }



        /** The hours along the bottom, every 3 hours where they fit and every 6 where not, with a faint line up. */
        private void hours(Canvas c, float left, float right, float top, float bottom, float h) {
            if (to <= from) return;
            int every = (right - left) / 8 >= 46 * dp ? 3 : 6;
            stroke.setStrokeWidth(Math.max(1, 0.6f * dp));
            stroke.setPathEffect(null);
            for (long[] m : hourMarks(from, to, zone, every)) {
                float xx = x(m[0], left, right);
                stroke.setColor(0x0FFFFFFF);
                c.drawLine(xx, top, xx, bottom, stroke);
                label(c, hourLabel((int) m[1]), xx, h - 2 * dp, 11, SolarViews.SUB, regular, Paint.Align.CENTER);
            }
        }

        private static float y(double v, double lo, double hi, float top, float bottom) {
            return bottom - (float) ((v - lo) / (hi - lo)) * (bottom - top);
        }
    }

    /**
     * The last 7 days, a column each with today last: how warm the room got (a bar from the day's low to its high,
     * the numbers at its ends where there's room) and, for a thermostat that heats or cools, how long it ran (a bar,
     * orange heating under blue cooling, its time above). The week's totals at the top right, in their colours. Short
     * (under {@link #FULL} tall, as with two or more thermostats), only how long it ran: the 24 hours above already
     * show the temperature.
     */
    static final class WeekView extends SolarViews.Base {
        private static final int N = 7;
        /** The height, in dp, both rows need. */
        static final int FULL = 130;
        private List<Thermostat.Day> days = new ArrayList<>();
        private List<Thermostat.Part> summary = new ArrayList<>();
        private boolean loaded, runs;
        private double lo, hi;
        private long most;
        private final String[] names = new String[N], letters = new String[N], lows = new String[N],
                highs = new String[N], times = new String[N];
        private final RectF rect = new RectF();

        WeekView(Context c) {
            super(c);
        }

        /**
         * @param days    {@link Thermostat#days}, 7 of them
         * @param runs    whether it heats or cools (else no runtime bars)
         * @param summary the week's heating and cooling, each in its colour
         */
        void set(List<Thermostat.Day> days, ZoneId zone, boolean runs, List<Thermostat.Part> summary) {
            this.days = days;
            this.runs = runs;
            this.summary = summary;
            loaded = true;
            lo = Double.MAX_VALUE;
            hi = -Double.MAX_VALUE;
            most = 3_600_000L;
            for (int i = 0; i < days.size() && i < N; i++) {
                Thermostat.Day d = days.get(i);
                boolean today = i == days.size() - 1;
                java.time.DayOfWeek dow = Instant.ofEpochMilli(d.start).atZone(zone).getDayOfWeek();
                names[i] = today ? "Today" : dow.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US);
                letters[i] = today ? "•" : names[i].substring(0, 1);
                lows[i] = Double.isNaN(d.low) ? "" : Thermostat.deg(d.low);
                highs[i] = Double.isNaN(d.high) ? "" : Thermostat.deg(d.high);
                times[i] = d.known ? Thermostat.brief(d.heat + d.cool) : "";
                if (!Double.isNaN(d.low)) {
                    lo = Math.min(lo, d.low);
                    hi = Math.max(hi, d.high);
                }
                most = Math.max(most, d.heat + d.cool);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            text.setLetterSpacing(0.1f);
            label(c, "7 DAYS", 0, sp(12), 11, SolarViews.SUB, medium, Paint.Align.LEFT);
            float titleW = text.measureText("7 DAYS");
            text.setLetterSpacing(0);
            if (!loaded || days.isEmpty()) {
                label(c, loaded ? "No history yet" : "Loading…", w / 2, h / 2 + 4 * dp, 12, SolarViews.SUB, regular,
                        Paint.Align.CENTER);
                return;
            }
            summary(c, summary, w, sp(12), titleW + 16 * dp);
            int n = Math.min(N, days.size());
            float colW = w / n, top = sp(12) + 12 * dp, bottom = h - sp(11) - 8 * dp;
            boolean wide = colW >= 44 * dp, temps = h >= FULL * dp || !runs;
            float split = !temps ? top : runs ? top + (bottom - top) * 0.56f : bottom;

            // How warm: low to high, on one scale for the week.
            float numH = wide ? sp(11) + 5 * dp : 2 * dp;
            float t0 = top + numH, t1 = split - numH - (runs ? 4 * dp : 0);
            double span = Math.max(hi - lo, 4), mid = (hi + lo) / 2, a = mid - span / 2, b = mid + span / 2;
            float barW = Math.min(10 * dp, colW * 0.3f);
            for (int i = 0; i < n; i++) {
                Thermostat.Day d = days.get(i);
                float x = colW * (i + 0.5f);
                boolean today = i == n - 1;
                if (temps && d.known && !Double.isNaN(d.low) && t1 > t0) {
                    float yHi = t1 - (float) ((d.high - a) / (b - a)) * (t1 - t0);
                    float yLo = t1 - (float) ((d.low - a) / (b - a)) * (t1 - t0);
                    rect.set(x - barW / 2, yHi - barW / 2, x + barW / 2, yLo + barW / 2);
                    fill.setColor(today ? 0xF2FFFFFF : 0x8CFFFFFF);
                    c.drawRoundRect(rect, barW / 2, barW / 2, fill);
                    if (wide) {
                        label(c, highs[i], x, rect.top - 5 * dp, 11, 0xD9FFFFFF, regular, Paint.Align.CENTER);
                        label(c, lows[i], x, rect.bottom + sp(11) + 2 * dp, 11, SolarViews.SUB, regular,
                                Paint.Align.CENTER);
                    }
                }

                // How long it ran: heating at the bottom, cooling on top.
                if (runs && d.known) {
                    float base = bottom, room = bottom - split - (wide ? sp(11) + 6 * dp : 2 * dp);
                    float bw = Math.min(colW * 0.42f, 20 * dp);
                    float hh = room * d.heat / most, ch = room * d.cool / most;
                    if (d.heat + d.cool == 0) {
                        fill.setColor(0x33FFFFFF);
                        rect.set(x - bw / 2, base - 2 * dp, x + bw / 2, base);
                        c.drawRect(rect, fill);
                    } else {
                        if (hh > 0) {
                            fill.setColor(Thermostat.HEATING);
                            rect.set(x - bw / 2, base - Math.max(hh, 2 * dp), x + bw / 2, base);
                            c.drawRoundRect(rect, 2 * dp, 2 * dp, fill);
                        }
                        if (ch > 0) {
                            fill.setColor(Thermostat.COOLING);
                            rect.set(x - bw / 2, base - hh - Math.max(ch, 2 * dp), x + bw / 2, base - hh);
                            c.drawRoundRect(rect, 2 * dp, 2 * dp, fill);
                        }
                    }
                    if (wide) {
                        label(c, times[i], x, base - Math.max(hh + ch, 2 * dp) - 5 * dp, 11, 0xD9FFFFFF, regular,
                                Paint.Align.CENTER);
                    }
                }

                // The day.
                int col = !d.known ? 0x4DFFFFFF : today ? 0xFFFFFFFF : SolarViews.SUB;
                label(c, wide ? names[i] : letters[i], x, h - 2 * dp, 11, col, today ? medium : regular,
                        Paint.Align.CENTER);
            }
        }
    }

    /** A step of 1, 2 or 5 times a power of ten, the smallest that cuts {@code span} into at most {@code most}. */
    static double niceStep(double span, int most) {
        double p = Math.pow(10, Math.floor(Math.log10(span / most)));
        for (double m : new double[] {1, 2, 5, 10}) if (span / (m * p) <= most) return m * p;
        return 10 * p;
    }

    /** Each whole {@code every} hours between {@code from} and {@code to}, as {epoch ms, hour of the day}. */
    static List<long[]> hourMarks(long from, long to, ZoneId zone, int every) {
        List<long[]> out = new ArrayList<>();
        // Stepped on the clock, not in elapsed hours, so a daylight saving day still marks 3a, 6a, 9a.
        ZonedDateTime start = Instant.ofEpochMilli(from).atZone(zone);
        java.time.LocalDateTime t = start.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
                .plusHours(every - start.getHour() % every);
        for (long ms; (ms = t.atZone(zone).toInstant().toEpochMilli()) < to; t = t.plusHours(every)) {
            if (out.isEmpty() || ms > out.get(out.size() - 1)[0]) out.add(new long[] {ms, t.atZone(zone).getHour()});
        }
        return out;
    }

    /** "12a", "6a", "12p", "9p". */
    static String hourLabel(int hr) {
        return hr == 0 ? "12a" : hr == 12 ? "12p" : (hr % 12) + (hr < 12 ? "a" : "p");
    }

    /** "1:35 PM" in Home Assistant's zone. */
    static String clock(ZoneId zone) {
        return ZonedDateTime.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US));
    }
}
