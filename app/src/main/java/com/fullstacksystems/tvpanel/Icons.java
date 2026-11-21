package com.fullstacksystems.tvpanel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The bundled Material Design Icons, by the names Ha uses ("thermometer", "weather_sunny", ...). */
final class Icons {
    static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "thermometer", "fish", "ph", "shaker_outline", "test_tube", "flask", "garage", "garage_open", "solar_power", "flash",
            "gauge", "water_percent", "water_outline", "lock", "lock_open_variant", "door_open", "door_closed",
            "weather_sunny", "weather_night", "weather_partly_cloudy", "weather_night_partly_cloudy", "weather_cloudy",
            "weather_fog", "weather_hail", "weather_lightning", "weather_lightning_rainy", "weather_pouring",
            "weather_rainy", "weather_snowy", "weather_snowy_rainy", "weather_windy", "weather_windy_variant",
            "weather_sunny_alert", "weather_sunset_up", "weather_sunset_down", "transmission_tower", "home_outline",
            "home_lightning_bolt_outline", "battery_outline", "battery_charging_outline", "white_balance_sunny", "cctv",
            "motion_sensor", "video_off_outline"));

    private Icons() {
    }

    static int res(Context c, String name) {
        int id = NAMES.contains(name) ? c.getResources().getIdentifier("mdi_" + name, "drawable", c.getPackageName()) : 0;
        return id != 0 ? id : R.drawable.mdi_gauge;
    }

    static Drawable tinted(Context c, String name, int colour) {
        Drawable d = c.getDrawable(res(c, name)).mutate();
        d.setTint(colour);
        return d;
    }

    /** The icon as a PNG for the launcher, which draws it in the chip. */
    static byte[] png(Context c, String name, int colour, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Drawable d = tinted(c, name, colour);
        d.setBounds(0, 0, px, px);
        d.draw(new Canvas(b));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return out.toByteArray();
    }
}
