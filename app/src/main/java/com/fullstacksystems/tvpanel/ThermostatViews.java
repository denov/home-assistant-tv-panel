package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.graphics.Canvas;
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

/** The thermostat and air quality screens' drawn parts: a thermostat's last 24 hours, and a sensor's sparkline. */
final class ThermostatViews {
    private ThermostatViews() {
    }

    /** Where the thermostat was over 24 hours, with its target band behind and its heating and cooling under. */
    static final class GraphView extends SolarViews.Base {
        private List<Thermostat.Point> points = new ArrayList<>();
        private List<Thermostat.Span> spans = new ArrayList<>();
        private long from, to;
        private ZoneId zone = ZoneId.systemDefault();
        private boolean loaded;
        private final Path line = new Path();
        private final Path band = new Path();
        private final RectF bar = new RectF();

        GraphView(Context c) {
            super(c);
        }

        void set(List<Thermostat.Point> points, long from, long to, ZoneId zone) {
            this.points = points;
            this.spans = Thermostat.spans(points, to);
            this.from = from;
            this.to = to;
            this.zone = zone;
            loaded = true;
            invalidate();
        }

        private float x(long t, float left, float right) {
            return left + (float) ((Math.max(t, from) - from) / (double) (to - from)) * (right - left);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            text.setLetterSpacing(0.1f);
            label(c, "LAST 24 HOURS", 0, sp(11), 10, SolarViews.SUB, medium, Paint.Align.LEFT);
            text.setLetterSpacing(0);
            float left = 30 * dp, right = w - 2 * dp, top = sp(11) + 10 * dp, strip = h - 16 * dp, bottom = strip - 6 * dp;
            stroke.setStrokeWidth(Math.max(1, 0.6f * dp));
            stroke.setColor(0x1FFFFFFF);
            c.drawLine(left, top, right, top, stroke);
            c.drawLine(left, bottom, right, bottom, stroke);

            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                Thermostat.Point p = points.get(i);
                // The row before the window is where it started, so it counts.
                if (i + 1 < points.size() && points.get(i + 1).t < from) continue;
                if (!Double.isNaN(p.cur)) {
                    lo = Math.min(lo, p.cur);
                    hi = Math.max(hi, p.cur);
                }
                if (!Double.isNaN(p.lo)) {
                    lo = Math.min(lo, p.lo);
                    hi = Math.max(hi, p.hi);
                }
            }
            if (lo > hi || to <= from) {
                label(c, loaded ? "No history yet" : "Loading…", (left + right) / 2, (top + bottom) / 2 + 4 * dp, 12,
                        SolarViews.SUB, regular, Paint.Align.CENTER);
                hours(c, left, right, h);
                return;
            }
            double pad = Math.max((hi - lo) * 0.15, 1);
            lo -= pad;
            hi += pad;
            label(c, Ha.number(Math.round(hi)) + "°", left - 4 * dp, top + 4 * dp, 9, SolarViews.SUB, regular, Paint.Align.RIGHT);
            label(c, Ha.number(Math.round(lo)) + "°", left - 4 * dp, bottom, 9, SolarViews.SUB, regular, Paint.Align.RIGHT);

            // The target band: where it was set, held until the next change. One shape per stretch with a target, so
            // the hundreds of rows a day has don't leave seams where their edges overlap.
            fill.setColor(0x33FFFFFF);
            band.reset();
            int start = -1;
            for (int i = 0; i <= points.size(); i++) {
                boolean has = i < points.size() && !Double.isNaN(points.get(i).lo) && !Double.isNaN(points.get(i).hi);
                if (has && start < 0) start = i;
                if (has || start < 0) continue;
                // Points start..i-1 have a target: the top edge left to right, then the bottom edge back.
                for (int j = start; j < i; j++) {
                    Thermostat.Point p = points.get(j);
                    float x0 = x(p.t, left, right), x1 = x(j + 1 < points.size() ? points.get(j + 1).t : to, left, right);
                    float yHi = Math.min(y(p.hi, lo, hi, top, bottom), y((p.lo + p.hi) / 2, lo, hi, top, bottom) - dp);
                    if (j == start) band.moveTo(x0, yHi);
                    else band.lineTo(x0, yHi);
                    band.lineTo(x1, yHi);
                }
                for (int j = i - 1; j >= start; j--) {
                    Thermostat.Point p = points.get(j);
                    float x0 = x(p.t, left, right), x1 = x(j + 1 < points.size() ? points.get(j + 1).t : to, left, right);
                    // One setpoint is a line of a band.
                    float yLo = Math.max(y(p.lo, lo, hi, top, bottom), y((p.lo + p.hi) / 2, lo, hi, top, bottom) + dp);
                    band.lineTo(x1, yLo);
                    band.lineTo(x0, yLo);
                }
                band.close();
                start = -1;
            }
            c.drawPath(band, fill);

            // Where it was: held until the next reading, and on to now.
            line.reset();
            boolean pen = false;
            for (int i = 0; i < points.size(); i++) {
                Thermostat.Point p = points.get(i);
                if (Double.isNaN(p.cur)) {
                    pen = false;
                    continue;
                }
                float yy = y(p.cur, lo, hi, top, bottom);
                float x0 = x(p.t, left, right), x1 = x(i + 1 < points.size() ? points.get(i + 1).t : to, left, right);
                if (!pen) line.moveTo(x0, yy);
                else line.lineTo(x0, yy);
                line.lineTo(x1, yy);
                pen = true;
            }
            stroke.setStrokeWidth(2 * dp);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(line, stroke);

            // Heating and cooling, along the bottom.
            float r = 2 * dp;
            for (Thermostat.Span s : spans) {
                float x0 = x(s.from, left, right), x1 = x(s.to, left, right);
                if (x1 - x0 < 1) continue;
                fill.setColor(s.colour);
                bar.set(x0, strip - 4 * dp, Math.max(x1, x0 + 2 * r), strip);
                c.drawRoundRect(bar, r, r, fill);
            }
            hours(c, left, right, h);
        }

        private static float y(double v, double lo, double hi, float top, float bottom) {
            return bottom - (float) ((v - lo) / (hi - lo)) * (bottom - top);
        }

        /** Every 6 hours along the bottom: "6a", "12p". */
        private void hours(Canvas c, float left, float right, float h) {
            if (to <= from) return;
            ZonedDateTime start = Instant.ofEpochMilli(from).atZone(zone);
            ZonedDateTime t = start.truncatedTo(ChronoUnit.HOURS).plusHours(6 - start.getHour() % 6);
            while (t.toInstant().toEpochMilli() < to) {
                float xx = x(t.toInstant().toEpochMilli(), left, right);
                int hr = t.getHour();
                String s = hr == 0 ? "12a" : hr == 12 ? "12p" : (hr % 12) + (hr < 12 ? "a" : "p");
                label(c, s, xx, h - 2 * dp, 9, SolarViews.SUB, regular, Paint.Align.CENTER);
                t = t.plusHours(6);
            }
        }
    }

    /** A sensor's line over its last hours, in one colour, without axes: the shape, not the numbers. */
    static final class SparkView extends SolarViews.Base {
        private List<Solar.Stat> rows = new ArrayList<>();
        private long from, to;
        private int colour = SolarViews.SUB;
        private final Path p = new Path();

        SparkView(Context c) {
            super(c);
        }

        void set(List<Solar.Stat> rows, long from, long to, int colour) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            this.from = from;
            this.to = to;
            this.colour = colour;
            invalidate();
        }

        /** A row older than the window that isn't where the window starts (the last one before it). */
        private boolean before(int i) {
            return rows.get(i).start < from && i + 1 < rows.size() && rows.get(i + 1).start <= from;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i < rows.size(); i++) {
                Solar.Stat s = rows.get(i);
                if (Double.isNaN(s.value) || before(i)) continue;
                lo = Math.min(lo, s.value);
                hi = Math.max(hi, s.value);
            }
            if (lo > hi || to <= from) return;
            // A flat line sits in the middle rather than on an edge.
            double span = hi - lo, pad = span == 0 ? Math.max(Math.abs(hi) * 0.05, 1) : span * 0.1;
            lo -= pad;
            hi += pad;
            float inset = 1.5f * dp;
            p.reset();
            boolean pen = false;
            float py = 0;
            for (int i = 0; i < rows.size(); i++) {
                Solar.Stat s = rows.get(i);
                if (before(i)) continue;
                if (Double.isNaN(s.value)) {
                    pen = false;
                    continue;
                }
                float px = (float) ((Math.max(s.start, from) - from) / (double) (to - from)) * w;
                py = inset + (float) ((hi - s.value) / (hi - lo)) * (h - 2 * inset);
                if (!pen) p.moveTo(px, py);
                else p.lineTo(px, py);
                pen = true;
            }
            // History only has changes: the last value holds until now.
            if (pen) p.lineTo(w, py);
            stroke.setStrokeWidth(1.6f * dp);
            stroke.setColor(colour);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(p, stroke);
        }
    }

    /** "1:35 PM" in Home Assistant's zone. */
    static String clock(ZoneId zone) {
        return ZonedDateTime.now(zone).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US));
    }
}
