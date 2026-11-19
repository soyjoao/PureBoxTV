package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IdleClockTest {
    @Test public void aChoiceMadeOnTheTvWinsOverTheTheme() {
        assertEquals(5, Config.chosenIdleClockMinutes(5, 15));
        assertEquals(30, Config.chosenIdleClockMinutes(30, 15));
        assertEquals(60, Config.chosenIdleClockMinutes(60, 15));
        assertEquals(0, Config.chosenIdleClockMinutes(0, 15));
    }

    @Test public void noChoiceKeepsTheThemeValue() {
        assertEquals(15, Config.chosenIdleClockMinutes(-1, 15));
        assertEquals(0, Config.chosenIdleClockMinutes(-1, 0));
    }

    @Test public void anOutOfRangeChoiceIsIgnored() {
        assertEquals(15, Config.chosenIdleClockMinutes(-2, 15));
        assertEquals(15, Config.chosenIdleClockMinutes(1441, 15));
    }
}
