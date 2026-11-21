package com.fullstacksystems.tvpanel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Who may read the chips: the garage door's state is nobody else's business. */
public class WidgetProviderTest {

    @Test
    public void theLaunchersAndTheAdbShellMayRead() {
        assertTrue(WidgetProvider.mayRead("com.fullstacksystems.tvhome")); // TV Panel Launcher
        assertTrue(WidgetProvider.mayRead("com.gathin.tvhome")); // CleanYourTV's launcher, with the widget row
        assertTrue(WidgetProvider.mayRead("com.android.shell")); // checks over ADB
    }

    @Test
    public void nobodyElseMay() {
        assertFalse(WidgetProvider.mayRead("com.example.spy"));
        assertFalse(WidgetProvider.mayRead(""));
        assertFalse(WidgetProvider.mayRead(null));
    }
}
