package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import android.os.Process;

import org.junit.Test;

public class WallpaperPlayerTest {
    @Test
    public void aRotationDecodesInTheBackgroundButAnEmptyScreenDoesNotWait() {
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, WallpaperPlayer.decodePriority(false));
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, WallpaperPlayer.decodePriority(true));
    }
}
