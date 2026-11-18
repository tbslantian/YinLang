package app.soundshelf;

import static org.junit.Assert.*;
import java.time.ZonedDateTime;
import org.junit.Test;

public class HomeTemplateTest {
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-11-18T12:15:00+08:00[Asia/Shanghai]");

    @Test public void manualSelectionDoesNotChangeWithClockOrAppearance() {
        for (HomeTemplate template : HomeTemplate.values()) {
            assertEquals(template, HomeTemplate.resolve(template.name(), "hourly", NOW));
            assertEquals(template, HomeTemplate.resolve(template.name(), "daily", NOW.plusDays(7)));
        }
    }

    @Test public void unknownPreferenceFallsBackToExistingHomepage() {
        assertEquals(HomeTemplate.CLASSIC, HomeTemplate.resolve("unknown", "daily", NOW));
    }

    @Test public void dailyRotationStaysStableAcrossNavigationAndChangesAtLocalMidnight() {
        var morning = NOW.withHour(0).withMinute(0);
        var current = HomeTemplate.resolve(HomeTemplate.AUTO, "daily", morning);
        assertEquals(current, HomeTemplate.resolve(HomeTemplate.AUTO, "daily", morning.plusHours(23).plusMinutes(59)));
        var next = HomeTemplate.resolve(HomeTemplate.AUTO, "daily", morning.plusDays(1));
        assertEquals(HomeTemplate.values()[(current.ordinal() + 1) % 3], next);
        assertEquals(current, HomeTemplate.resolve(HomeTemplate.AUTO, "daily", morning.plusDays(3)));
    }

    @Test public void hourlyRotationChangesOnlyAtTheNextHourAndCyclesAllThreeStyles() {
        var hour = NOW.withMinute(0);
        var current = HomeTemplate.resolve(HomeTemplate.AUTO, "hourly", hour);
        assertEquals(current, HomeTemplate.resolve(HomeTemplate.AUTO, "hourly", hour.plusMinutes(59)));
        assertEquals(HomeTemplate.values()[(current.ordinal() + 1) % 3],
            HomeTemplate.resolve(HomeTemplate.AUTO, "hourly", hour.plusHours(1)));
        assertEquals(current, HomeTemplate.resolve(HomeTemplate.AUTO, "hourly", hour.plusHours(3)));
    }

    @Test public void invalidRotationFallsBackToDailyAndPreEpochDatesRemainValid() {
        assertEquals(HomeTemplate.resolve(HomeTemplate.AUTO, "daily", NOW), HomeTemplate.resolve(HomeTemplate.AUTO, "invalid", NOW));
        assertNotNull(HomeTemplate.resolve(HomeTemplate.AUTO, "hourly", NOW.withYear(1960)));
        assertNotNull(HomeTemplate.resolve(HomeTemplate.AUTO, "daily", NOW.withYear(1960)));
    }
}
