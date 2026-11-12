package com.hermesvox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #569 — a field log parses the same on every handset.
 *
 * The bug: `"%.2f".format(rtf)` uses the device's default locale, so a French phone wrote
 * `rtf=0,68`. A comma is where a number ends for every reader of the log — `grep`, the
 * field-log counter, an issue quoting the line — and the value silently became `0`. The
 * same expression sat on eight log lines.
 *
 * The guard is [VoxLog.num], and this test moves the *default* locale to one that writes a
 * comma: a regression back to `"%.2f".format` fails here, on the build host, with no phone.
 */
class VoxLogNumTest {

    private lateinit var was: Locale

    @Before fun frenchDefault() {
        was = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
    }

    @After fun restore() { Locale.setDefault(was) }

    @Test fun theDefaultLocaleWouldHaveWritttenAComma() {
        // The defect, stated: this is what the old expression produces on the same handset.
        assertEquals("0,68", String.format("%.2f", 0.68f))
    }

    @Test fun aLoggedDecimalAlwaysCarriesADot() {
        assertEquals("0.68", VoxLog.num(0.68f))
        assertEquals("0.680", VoxLog.num(0.68f, 3))
        assertEquals("1.00", VoxLog.num(1f))
        assertEquals("1234.50", VoxLog.num(1234.5f))
        // The reader's rule: after `rtf=`, a value ends at the first character that is not
        // a digit or a dot.
        val line = "event=stt-rtf seg=1 audioMs=960 feedDecodeMs=653 finishMs=0 rtf=${VoxLog.num(0.68f)}"
        assertEquals("0.68", Regex("rtf=([0-9.]+)").find(line)!!.groupValues[1])
    }
}
