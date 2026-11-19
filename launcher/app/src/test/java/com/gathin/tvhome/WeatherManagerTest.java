package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WeatherManagerTest {
    @Test public void formatsPortugueseAndEnglishWeatherLines() {
        String pt = WeatherManager.formatLine(24, 0, true, "São Paulo", true);
        assertTrue(pt.contains("24°C"));
        assertTrue(pt.contains("Céu limpo"));
        assertTrue(pt.contains("São Paulo"));

        String en = WeatherManager.formatLine(18, 61, false, "London", false);
        assertTrue(en.contains("18°C"));
        assertTrue(en.contains("Rain"));
        assertTrue(en.contains("London"));
    }

    @Test public void omitsCitySeparatorWhenCityIsEmpty() {
        String line = WeatherManager.formatLine(21, 3, true, "", true);
        assertEquals("\u2601\uFE0F 21°C · Nublado", line);
    }
}
