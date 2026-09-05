package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the R2 backend-selection chain (resolveSttLeg): the privacy hard gate —
 * `remote` is chosen ONLY when the user saved a genuine http(s) URL (a scheme-
 * less/blank value is "not configured", so audio never leaves the device by
 * default) — and the sovereignty fallback order remote -> on-device (installed)
 * -> platform is never broken.
 */
class SttBackendChainTest {

    @Test fun platform_backend_is_always_platform() {
        assertEquals(ModelCatalog.BACKEND_PLATFORM, resolveSttLeg(ModelCatalog.BACKEND_PLATFORM, "http://h:1", true))
        assertEquals(ModelCatalog.BACKEND_PLATFORM, resolveSttLeg(ModelCatalog.BACKEND_PLATFORM, "", false))
    }

    @Test fun remote_with_saved_url_is_used_even_without_on_device_model() {
        assertEquals(ModelCatalog.BACKEND_REMOTE,
            resolveSttLeg(ModelCatalog.BACKEND_REMOTE, "http://192.168.1.9:13305/v1", false))
    }

    @Test fun remote_with_blank_url_never_leaves_the_device() {
        for (unset in listOf(null, "", "   ")) {
            assertEquals("backend=remote url=\"$unset\" model installed",
                ModelCatalog.BACKEND_ONDEVICE, resolveSttLeg(ModelCatalog.BACKEND_REMOTE, unset, true))
            assertEquals("backend=remote url=\"$unset\" no model",
                ModelCatalog.BACKEND_PLATFORM, resolveSttLeg(ModelCatalog.BACKEND_REMOTE, unset, false))
        }
    }

    @Test fun remote_with_scheme_less_url_is_not_configured() {
        // "192.168.1.9:13305" / "host.lan" without a scheme must NEVER arm remote
        // (an unparseable URL would otherwise crash or silently send nowhere).
        assertEquals(ModelCatalog.BACKEND_ONDEVICE, resolveSttLeg(ModelCatalog.BACKEND_REMOTE, "192.168.1.9:13305", true))
        assertEquals(ModelCatalog.BACKEND_PLATFORM, resolveSttLeg(ModelCatalog.BACKEND_REMOTE, "host.lan", false))
    }

    @Test fun on_device_default_requires_an_installed_model_else_platform() {
        assertEquals(ModelCatalog.BACKEND_ONDEVICE, resolveSttLeg(null, "http://x", true))
        assertEquals(ModelCatalog.BACKEND_PLATFORM, resolveSttLeg(null, "http://x", false))
        assertEquals(ModelCatalog.BACKEND_ONDEVICE, resolveSttLeg(ModelCatalog.BACKEND_ONDEVICE, "", true))
        assertEquals(ModelCatalog.BACKEND_PLATFORM, resolveSttLeg(ModelCatalog.BACKEND_ONDEVICE, "", false))
    }
}
