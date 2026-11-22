package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The solar screen's drawn parts: the energy flow, the panel map and today's chart. */
final class SolarViews {
    static final int SOLAR = 0xFF22B8E8;
    static final int HOME = 0xFFF5813A;
    static final int GRID = 0xFFA4ADB8;
    static final int BATTERY = 0xFF7DCB4A;
    static final int DIM = 0x40FFFFFF;
    static final int SUB = 0x99FFFFFF;

    private SolarViews() {
    }

    /** Shared paint and unit helpers. */
    abstract static class Base extends View {
        final float dp;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);

        Base(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
        }

        float sp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
        }

        void label(Canvas c, String s, float x, float y, float size, int colour, Typeface face, Paint.Align align) {
            text.setTextSize(sp(size));
            text.setColor(colour);
            text.setTypeface(face);
            text.setTextAlign(align);
            c.drawText(s, x, y, text);
        }

        /**
         * Runs of text in their colours, right-aligned at {@code right} on {@code base}, 12 sp, when they fit right of
         * {@code minX}.
         */
        void summary(Canvas c, java.util.List<Thermostat.Part> parts, float right, float base, float minX) {
            if (parts == null || parts.isEmpty()) return;
            text.setTextSize(sp(12));
            text.setTypeface(regular);
            text.setTextAlign(Paint.Align.LEFT);
            float total = 0;
            for (Thermostat.Part p : parts) total += text.measureText(p.text);
            if (right - total < minX) return;
            float x = right - total;
            for (Thermostat.Part p : parts) {
                text.setColor(p.colour);
                c.drawText(p.text, x, base, text);
                x += text.measureText(p.text);
            }
        }

        void icon(Canvas c, String name, int colour, float cx, float cy, float size) {
            Drawable d = Icons.tinted(getContext(), name, colour);
            int h = Math.round(size / 2);
            d.setBounds(Math.round(cx) - h, Math.round(cy) - h, Math.round(cx) + h, Math.round(cy) + h);
            d.draw(c);
        }
    }

    // ---- flow --------------------------------------------------------------------------------------------------

    /** Grid, solar and battery above the house, each with its power now and today's energy, like Enphase's flow. */
    static final class FlowView extends Base {
        private Solar.Live live;
        private Solar.Totals totals;

        FlowView(Context c) {
            super(c);
        }

        void set(Solar.Live live, Solar.Totals totals) {
            this.live = live;
            this.totals = totals;
            invalidate();
        }

        private double today(int metric) {
            return totals == null ? Double.NaN : totals.today[metric];
        }

        @Override
        protected void onDraw(Canvas c) {
            if (live == null) return;
            float w = getWidth(), h = getHeight();
            float r = 21 * dp;
            float top = r + 2 * dp;
            boolean battery = live.hasBattery();
            float gx = w * (battery ? 0.17f : 0.25f), sx = w * (battery ? 0.5f : 0.75f), bx = w * 0.83f;
            float ring = Math.min(42 * dp, h * 0.19f);
            float hy = h - ring - 22 * dp;
            float hx = w / 2;

            double grid = live.gridW();
            double bat = live.batteryW();
            double sol = live.productionW;

            // Lines first, so the nodes sit on top of them.
            float lineTop = top + r + 52 * dp;
            flowLine(c, gx, lineTop, hx, hy, ring, -1, GRID, grid, true);
            flowLine(c, sx, lineTop, hx, hy, ring, battery ? 0 : 1, SOLAR, Double.isNaN(sol) ? 0 : sol, false);
            if (battery) flowLine(c, bx, lineTop, hx, hy, ring, 1, BATTERY, bat, true);

            // Grid.
            String gridWord = Double.isNaN(grid) ? "Grid" : grid > 20 ? "Importing" : grid < -20 ? "Exporting" : "Grid";
            node(c, gx, top, r, "transmission_tower", GRID, gridWord, Solar.power(Double.isNaN(grid) ? grid : Math.abs(grid)),
                    Solar.energy(today(Solar.IMPORTED)) + " in · " + Solar.energy(today(Solar.EXPORTED)) + " out");
            // Solar.
            node(c, sx, top, r, "white_balance_sunny", SOLAR, "Solar", Solar.power(sol),
                    Solar.energy(today(Solar.PRODUCED)) + " kWh");
            // Battery.
            if (battery) {
                String word = bat > 20 ? "Discharging" : bat < -20 ? "Charging" : "Battery";
                String pct = Double.isNaN(live.soc) ? "" : Math.round(live.soc) + "%";
                String now = Math.abs(bat) > 20 ? Solar.power(Math.abs(bat)) : pct.isEmpty() ? "Idle" : pct;
                if (Math.abs(bat) > 20 && !pct.isEmpty()) word = word + " · " + pct;
                node(c, bx, top, r, bat < -20 ? "battery_charging_outline" : "battery_outline", BATTERY, word, now,
                        Solar.energy(today(Solar.CHARGED)) + " in · " + Solar.energy(today(Solar.DISCHARGED)) + " out");
            }

            // House ring: where today's energy came from (solar, battery, grid), like Enphase's.
            double cons = today(Solar.CONSUMED);
            double fromGrid = Math.max(0, today(Solar.IMPORTED));
            double fromBat = battery ? Math.max(0, today(Solar.DISCHARGED)) : 0;
            stroke.setStrokeWidth(5 * dp);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            RectF o = new RectF(hx - ring, hy - ring, hx + ring, hy + ring);
            if (!Double.isNaN(cons) && cons > 0) {
                double g = Double.isNaN(fromGrid) ? 0 : Math.min(1, fromGrid / cons);
                double b = Double.isNaN(fromBat) ? 0 : Math.min(1 - g, fromBat / cons);
                double s = Math.max(0, 1 - g - b);
                float a = -90;
                a = arc(c, o, a, s, SOLAR);
                a = arc(c, o, a, b, BATTERY);
                arc(c, o, a, g, GRID);
            } else {
                stroke.setColor(DIM);
                c.drawOval(o, stroke);
            }
            stroke.setStrokeCap(Paint.Cap.ROUND);
            icon(c, "home_outline", HOME, hx, hy - ring * 0.38f, ring * 0.62f);
            label(c, Solar.power(live.consumptionW), hx, hy + ring * 0.32f, 15, Color.WHITE, medium, Paint.Align.CENTER);
            label(c, "Home", hx, hy + ring * 0.66f, 11, SUB, regular, Paint.Align.CENTER);
            label(c, Solar.energy(cons) + " kWh used today", hx, hy + ring + 17 * dp, 12, HOME, regular, Paint.Align.CENTER);
        }

        private float arc(Canvas c, RectF o, float start, double share, int colour) {
            if (share <= 0.001) return start;
            float sweep = (float) (share * 360);
            stroke.setColor(colour);
            c.drawArc(o, start, sweep, false, stroke);
            return start + sweep;
        }

        private void node(Canvas c, float x, float top, float r, String icon, int colour, String word, String now, String today) {
            stroke.setStrokeWidth(2 * dp);
            stroke.setColor(colour);
            fill.setColor(0x14FFFFFF);
            c.drawCircle(x, top, r, fill);
            c.drawCircle(x, top, r, stroke);
            icon(c, icon, colour, x, top, r * 1.05f);
            float y = top + r + 15 * dp;
            label(c, word, x, y, 11, SUB, regular, Paint.Align.CENTER);
            label(c, now, x, y + 18 * dp, 16, Color.WHITE, medium, Paint.Align.CENTER);
            label(c, today, x, y + 33 * dp, 11, colour, regular, Paint.Align.CENTER);
        }

        /**
         * From a node down to the house ring. {@code side}: where it meets the ring (-1 left, 0 top, 1 right).
         * {@code signed}: positive power flows toward the house, negative away from it.
         */
        private void flowLine(Canvas c, float x, float y0, float hx, float hy, float ring, int side, int colour,
                              double watts, boolean signed) {
            boolean active = !Double.isNaN(watts) && Math.abs(watts) > 20;
            stroke.setStrokeWidth(2 * dp);
            stroke.setColor(active ? colour : DIM);
            float ex = hx + side * ring * 0.72f, ey = hy - ring * (side == 0 ? 1 : 0.72f);
            Path p = new Path();
            p.moveTo(x, y0);
            if (side == 0 && Math.abs(x - hx) < 1) {
                p.lineTo(ex, ey);
            } else {
                float bend = Math.min(18 * dp, Math.abs(ey - y0) / 2);
                p.lineTo(x, ey - bend);
                p.quadTo(x, ey, x + Math.signum(ex - x) * bend, ey);
                p.lineTo(ex, ey);
            }
            c.drawPath(p, stroke);
            if (!active) return;
            boolean toHouse = !signed || watts > 0;
            // Arrowhead on the vertical part, pointing along the flow.
            float ax = x, ay = (y0 + ey) / 2 - 4 * dp;
            if (side == 0 && Math.abs(x - hx) < 1) ay = (y0 + ey) / 2;
            float s = 5 * dp * (toHouse ? 1 : -1);
            Path arrow = new Path();
            arrow.moveTo(ax - 5 * dp, ay - s / 2);
            arrow.lineTo(ax, ay + s / 2);
            arrow.lineTo(ax + 5 * dp, ay - s / 2);
            c.drawPath(arrow, stroke);
        }
    }

    // ---- panels ------------------------------------------------------------------------------------------------

    /** The arrays as the owner laid them out, each panel shaded by its output, like Enphase's array view. */
    static final class PanelMapView extends Base {
        private List<Solar.Group> groups = Collections.emptyList();
        private double[] size = {1, 1};
        private Map<String, Double> now = Collections.emptyMap();
        private Map<String, Double> today = Collections.emptyMap();
        private boolean showToday;

        PanelMapView(Context c) {
            super(c);
        }

        void setLayout(List<Solar.Group> groups) {
            this.groups = groups;
            this.size = Solar.place(groups, 0.9);
            invalidate();
        }

        void setValues(Map<String, Double> now, Map<String, Double> today) {
            if (now != null) this.now = now;
            if (today != null) this.today = today;
            invalidate();
        }

        void setShowToday(boolean showToday) {
            this.showToday = showToday;
            invalidate();
        }

        boolean showToday() {
            return showToday;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (groups.isEmpty()) {
                label(c, "No inverters in Home Assistant.", getWidth() / 2f, getHeight() / 2f, 14, SUB, regular, Paint.Align.CENTER);
                return;
            }
            float labelH = 16 * dp;
            float unit = (float) Math.min(getWidth() / size[0], (getHeight() - labelH) / size[1]);
            float ox = (float) (getWidth() - size[0] * unit) / 2;
            float oy = labelH + (float) (getHeight() - labelH - size[1] * unit) / 2;
            Map<String, Double> values = showToday ? today : now;
            double max = 0;
            for (Double v : values.values()) if (v != null && !Double.isNaN(v)) max = Math.max(max, v);
            float gap = Math.max(1.5f * dp, unit * 0.05f);
            float textSize = Math.min(sp(11), unit * 0.40f);
            for (Solar.Group g : groups) {
                float gx = ox + (float) g.x * unit, gy = oy + (float) g.y * unit;
                text.setTextSize(sp(11));
                text.setColor(SUB);
                text.setTypeface(medium);
                text.setTextAlign(Paint.Align.LEFT);
                c.drawText(g.name, gx, gy - 5 * dp, text);
                for (Solar.Panel p : g.panels) {
                    RectF r = new RectF(gx + (float) p.x * unit + gap / 2, gy + (float) p.y * unit + gap / 2,
                            gx + (float) (p.x + p.w) * unit - gap / 2, gy + (float) (p.y + p.h) * unit - gap / 2);
                    Double v = values.get(p.serial);
                    boolean known = v != null && !Double.isNaN(v);
                    float share = known && max > 0 ? (float) (v / max) : 0;
                    fill.setColor(known ? shade(share) : 0xFF2A2F36);
                    c.drawRoundRect(r, 2 * dp, 2 * dp, fill);
                    // The cell lines of a panel, faintly.
                    stroke.setStrokeWidth(Math.max(1, 0.5f * dp));
                    stroke.setColor(0x22FFFFFF);
                    if (p.h > p.w) c.drawLine(r.left, r.centerY(), r.right, r.centerY(), stroke);
                    else c.drawLine(r.centerX(), r.top, r.centerX(), r.bottom, stroke);
                    stroke.setColor(0x40FFFFFF);
                    c.drawRoundRect(r, 2 * dp, 2 * dp, stroke);
                    String s = !known ? "—" : showToday ? String.format(Locale.US, "%.2f", v) : String.valueOf(Math.round(v));
                    text.setTextSize(textSize);
                    text.setTypeface(medium);
                    text.setTextAlign(Paint.Align.CENTER);
                    text.setColor(share > 0.55f ? 0xFFFFFFFF : 0xE6FFFFFF);
                    c.drawText(s, r.centerX(), r.centerY() + textSize * 0.36f, text);
                }
            }
        }

        /** Dark navy for little output, bright blue for the best panel. */
        private static int shade(float t) {
            t = Math.max(0, Math.min(1, t));
            int a = 0xFF0B2C4F, b = 0xFF2F9BF2;
            int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
            int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
            int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
    }

    // ---- today's chart -----------------------------------------------------------------------------------------

    /** Produced above the line and consumed below, per 15 minutes, with the battery's charge underneath. */
    static final class ChartView extends Base {
        private double[] produced, consumed, soc;
        private int nowIndex = -1;

        ChartView(Context c) {
            super(c);
        }

        void set(double[] produced, double[] consumed, double[] soc, int nowIndex) {
            this.produced = produced;
            this.consumed = consumed;
            this.soc = soc;
            this.nowIndex = nowIndex;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (produced == null) {
                label(c, "Loading…", getWidth() / 2f, getHeight() / 2f, 13, SUB, regular, Paint.Align.CENTER);
                return;
            }
            boolean hasSoc = false;
            if (soc != null) for (double v : soc) if (!Double.isNaN(v)) { hasSoc = true; break; }
            float left = 34 * dp, right = getWidth() - 4 * dp;
            float xLabels = 13 * dp;
            float socH = hasSoc ? 22 * dp : 0;
            float socGap = hasSoc ? 8 * dp : 0;
            float top = 6 * dp, bottom = getHeight() - xLabels - socH - socGap;
            float mid = (top + bottom) / 2;
            float slot = (right - left) / 96f;

            double max = 0.05;
            for (int i = 0; i < 96; i++) {
                max = Math.max(max, produced[i]);
                if (consumed != null) max = Math.max(max, consumed[i]);
            }
            max = Solar.nice(max);
            float half = (bottom - top) / 2;

            // Axis: max, 0, max (kWh per 15 minutes).
            stroke.setStrokeWidth(Math.max(1, dp * 0.6f));
            stroke.setColor(0x26FFFFFF);
            c.drawLine(left, mid, right, mid, stroke);
            c.drawLine(left, top, right, top, stroke);
            c.drawLine(left, bottom, right, bottom, stroke);
            String m = String.format(Locale.US, max < 1 ? "%.2f" : "%.1f", max);
            label(c, m, left - 5 * dp, top + 4 * dp, 9, SUB, regular, Paint.Align.RIGHT);
            label(c, "0", left - 5 * dp, mid + 3 * dp, 9, SUB, regular, Paint.Align.RIGHT);
            label(c, m, left - 5 * dp, bottom, 9, SUB, regular, Paint.Align.RIGHT);
            label(c, "kWh", left - 5 * dp, (top + mid) / 2 + 3 * dp, 9, 0x66FFFFFF, regular, Paint.Align.RIGHT);

            float bar = Math.max(1, slot * 0.72f);
            for (int i = 0; i < 96; i++) {
                if (nowIndex >= 0 && i > nowIndex) break;
                float x = left + slot * i + (slot - bar) / 2;
                float up = (float) (produced[i] / max) * half;
                if (up > 0.5f) {
                    fill.setColor(SOLAR);
                    c.drawRect(x, mid - up, x + bar, mid, fill);
                }
                float down = consumed == null ? 0 : (float) (consumed[i] / max) * half;
                if (down > 0.5f) {
                    fill.setColor(HOME);
                    c.drawRect(x, mid, x + bar, mid + down, fill);
                }
            }

            // Battery charge strip: 0-100%.
            if (hasSoc) {
                float st = bottom + socGap, sb = st + socH;
                fill.setColor(0x14FFFFFF);
                c.drawRect(left, st, right, sb, fill);
                Path area = new Path();
                boolean started = false;
                float lastX = left;
                for (int i = 0; i < 96; i++) {
                    if (Double.isNaN(soc[i])) continue;
                    float x = left + slot * (i + 0.5f);
                    float y = sb - (float) (Math.max(0, Math.min(100, soc[i])) / 100) * socH;
                    if (!started) {
                        area.moveTo(x, sb);
                        area.lineTo(x, y);
                        started = true;
                    } else {
                        area.lineTo(x, y);
                    }
                    lastX = x;
                }
                area.lineTo(lastX, sb);
                area.close();
                fill.setColor(0xCC7DCB4A);
                c.drawPath(area, fill);
                label(c, "100%", left - 5 * dp, st + 7 * dp, 8, SUB, regular, Paint.Align.RIGHT);
                label(c, "0%", left - 5 * dp, sb, 8, SUB, regular, Paint.Align.RIGHT);
            }

            // Now.
            if (nowIndex >= 0 && nowIndex < 96) {
                float x = left + slot * (nowIndex + 1);
                stroke.setColor(0x59FFFFFF);
                c.drawLine(x, top, x, bottom + socGap + socH, stroke);
            }

            // Hours.
            String[] marks = {"12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p", "12a"};
            for (int i = 0; i < marks.length; i++) {
                float x = left + slot * 12 * i;
                Paint.Align a = i == 0 ? Paint.Align.LEFT : i == marks.length - 1 ? Paint.Align.RIGHT : Paint.Align.CENTER;
                label(c, marks[i], x, getHeight() - 1 * dp, 9, SUB, regular, a);
            }
        }
    }
}
