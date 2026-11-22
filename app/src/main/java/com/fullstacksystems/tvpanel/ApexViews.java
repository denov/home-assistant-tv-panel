package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The tank screen's drawn parts: a trend chart per measurement and the level bars for containers. */
final class ApexViews {
    static final int LINE_1 = Ha.BLUE;
    static final int LINE_2 = 0xFFFF9F43;
    static final int WARN = 0xFFFF9F43;
    static final int BAD = 0xFFFF6B6B;

    private ApexViews() {
    }

    /** One line on a trend: times (epoch ms) and values, with its name and colour. */
    static final class Line {
        final String name;
        final int colour;
        final long[] t;
        final double[] v;

        Line(String name, int colour, List<Solar.Stat> rows) {
            this.name = name;
            this.colour = colour;
            t = new long[rows.size()];
            v = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                t[i] = rows.get(i).start;
                v[i] = rows.get(i).value;
            }
        }
    }

    /** A titled line chart over 24 hours or 7 days, with the readings now at the top right. */
    static final class TrendView extends SolarViews.Base {
        private String title = "";
        private final List<String> now = new ArrayList<>();
        private final List<Integer> nowColours = new ArrayList<>();
        private List<Line> lines = new ArrayList<>();
        private long from, to;
        private boolean days;
        private ZoneId zone = ZoneId.systemDefault();
        private int decimals = 1;

        TrendView(Context c) {
            super(c);
        }

        void setTitle(String title) {
            this.title = title.toUpperCase(Locale.US);
            invalidate();
        }

        /** The readings now, one per line, drawn in the lines' colours. */
        void setNow(List<String> values, List<Integer> colours) {
            now.clear();
            now.addAll(values);
            nowColours.clear();
            nowColours.addAll(colours);
            invalidate();
        }

        void setLines(List<Line> lines, long from, long to, boolean days, ZoneId zone, int decimals) {
            this.lines = lines;
            this.from = from;
            this.to = to;
            this.days = days;
            this.zone = zone;
            this.decimals = decimals;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            text.setLetterSpacing(0.1f);
            label(c, title, 0, sp(12), 11, SolarViews.SUB, medium, Paint.Align.LEFT);
            text.setLetterSpacing(0);
            float x = w;
            text.setTextSize(sp(15));
            text.setTypeface(medium);
            for (int i = now.size() - 1; i >= 0; i--) {
                String s = now.get(i);
                label(c, s, x, sp(14), 15, nowColours.get(i), medium, Paint.Align.RIGHT);
                text.setTextSize(sp(15));
                x -= text.measureText(s) + 12 * dp;
            }

            float left = 38 * dp, right = w - 2 * dp, top = sp(14) + 12 * dp, bottom = h - 14 * dp;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (Line l : lines) {
                for (int i = 0; i < l.v.length; i++) {
                    if (l.t[i] < from || Double.isNaN(l.v[i])) continue;
                    lo = Math.min(lo, l.v[i]);
                    hi = Math.max(hi, l.v[i]);
                }
            }
            stroke.setStrokeWidth(Math.max(1, 0.6f * dp));
            stroke.setColor(0x1FFFFFFF);
            c.drawLine(left, top, right, top, stroke);
            c.drawLine(left, bottom, right, bottom, stroke);
            c.drawLine(left, (top + bottom) / 2, right, (top + bottom) / 2, stroke);
            if (lo > hi || to <= from) {
                label(c, lines.isEmpty() ? "Loading…" : "No history yet", (left + right) / 2, (top + bottom) / 2 + 4 * dp,
                        12, SolarViews.SUB, regular, Paint.Align.CENTER);
                hours(c, left, right, h);
                return;
            }
            double span = hi - lo;
            double pad = Math.max(span * 0.12, Math.abs(hi) * 0.002 + Math.pow(10, -decimals));
            // Padding never takes an axis below zero when nothing is negative.
            lo = lo >= 0 ? Math.max(0, lo - pad) : lo - pad;
            hi += pad;
            String fmt = "%." + decimals + "f";
            label(c, String.format(Locale.US, fmt, hi), left - 5 * dp, top + 4 * dp, 9, SolarViews.SUB, regular, Paint.Align.RIGHT);
            label(c, String.format(Locale.US, fmt, (hi + lo) / 2), left - 5 * dp, (top + bottom) / 2 + 3 * dp, 9,
                    SolarViews.SUB, regular, Paint.Align.RIGHT);
            label(c, String.format(Locale.US, fmt, lo), left - 5 * dp, bottom, 9, SolarViews.SUB, regular, Paint.Align.RIGHT);

            // Older lines first, so the main probe is drawn on top.
            long gapMs = days ? 3 * 3_600_000L : 20 * 60_000L;
            for (int li = lines.size() - 1; li >= 0; li--) {
                Line l = lines.get(li);
                Path p = new Path();
                boolean pen = false;
                long last = Long.MIN_VALUE;
                for (int i = 0; i < l.v.length; i++) {
                    if (l.t[i] < from || Double.isNaN(l.v[i])) continue;
                    float px = left + (float) ((l.t[i] - from) / (double) (to - from)) * (right - left);
                    float py = bottom - (float) ((l.v[i] - lo) / (hi - lo)) * (bottom - top);
                    if (!pen || l.t[i] - last > gapMs) p.moveTo(px, py);
                    else p.lineTo(px, py);
                    pen = true;
                    last = l.t[i];
                }
                stroke.setStrokeWidth(2 * dp);
                stroke.setColor(li == 0 ? l.colour : (l.colour & 0x00FFFFFF) | 0xB3000000);
                stroke.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(p, stroke);
            }
            hours(c, left, right, h);
        }

        /** Time marks along the bottom: every 6 hours, or each day's name. */
        private void hours(Canvas c, float left, float right, float h) {
            if (to <= from) return;
            ZonedDateTime start = Instant.ofEpochMilli(from).atZone(zone);
            ZonedDateTime t = days ? start.truncatedTo(ChronoUnit.DAYS).plusDays(1)
                    : start.truncatedTo(ChronoUnit.HOURS).plusHours(6 - start.getHour() % 6);
            while (t.toInstant().toEpochMilli() < to) {
                float x = left + (float) ((t.toInstant().toEpochMilli() - from) / (double) (to - from)) * (right - left);
                String s;
                if (days) {
                    // The day's name in the middle of its stretch.
                    float mid = x + (right - left) / 7f / 2;
                    if (mid < right - 8 * dp) {
                        label(c, t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US), mid, h - 2 * dp, 9,
                                SolarViews.SUB, regular, Paint.Align.CENTER);
                    }
                    stroke.setColor(0x14FFFFFF);
                    c.drawLine(x, sp(14) + 12 * dp, x, h - 14 * dp, stroke);
                    t = t.plusDays(1);
                    continue;
                }
                int hr = t.getHour();
                s = hr == 0 ? "12a" : hr == 12 ? "12p" : (hr % 12) + (hr < 12 ? "a" : "p");
                label(c, s, x, h - 2 * dp, 9, SolarViews.SUB, regular, Paint.Align.CENTER);
                t = t.plusHours(6);
            }
        }
    }

    /** A thin level bar: how full (or how much is left), in the colour that says whether it needs attention. */
    static final class BarView extends SolarViews.Base {
        private double share = Double.NaN;
        private int colour = LINE_1;

        BarView(Context c) {
            super(c);
        }

        void set(double share, int colour) {
            this.share = share;
            this.colour = colour;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = getHeight() / 2f;
            RectF track = new RectF(0, 0, getWidth(), getHeight());
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(track, r, r, fill);
            if (Double.isNaN(share)) return;
            float w = (float) Math.max(0, Math.min(1, share)) * getWidth();
            if (w <= 0) return;
            fill.setColor(colour);
            c.drawRoundRect(new RectF(0, 0, Math.max(w, 2 * r), getHeight()), r, r, fill);
        }
    }
}
