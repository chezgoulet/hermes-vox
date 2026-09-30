package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunRouteTest {
    @Test fun firstRunWithNoEndpointOnboards() {
        assertTrue(FirstRunRoute.shouldOnboard("", skipped = false))
        assertTrue(FirstRunRoute.shouldOnboard(null, skipped = false))
        assertTrue(FirstRunRoute.shouldOnboard("   ", skipped = false))
    }

    @Test fun aRecordedSkipIsHonored() {
        // The loop bug: "Skip anyway" -> Main -> onboarding again.
        assertFalse(FirstRunRoute.shouldOnboard("", skipped = true))
    }

    @Test fun aConfiguredEndpointNeverOnboards() {
        assertFalse(FirstRunRoute.shouldOnboard("https://gw.example.ts.net", skipped = false))
        assertFalse(FirstRunRoute.shouldOnboard("https://gw.example.ts.net", skipped = true))
    }
}
