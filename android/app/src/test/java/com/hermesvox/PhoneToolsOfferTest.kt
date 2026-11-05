package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-run offer is one moment, not a nag: eligible only once, and only after a real connection. */
class PhoneToolsOfferTest {

    @Test fun the_offer_needs_a_verified_connection_presence_and_never_having_been_shown() {
        assertTrue(PhoneToolsOffer.shouldOffer(connectionVerified = true, presenceOn = true, offered = false))
        assertFalse(PhoneToolsOffer.shouldOffer(connectionVerified = false, presenceOn = true, offered = false))
        assertFalse(PhoneToolsOffer.shouldOffer(connectionVerified = true, presenceOn = false, offered = false))
        assertFalse(PhoneToolsOffer.shouldOffer(connectionVerified = true, presenceOn = true, offered = true))
    }

    @Test fun the_offer_is_also_behind_the_phone_tools_set() {
        assertFalse(
            "the offer must not open a sheet the gate keeps unreachable",
            PhoneToolsOffer.shouldOffer(
                connectionVerified = true,
                presenceOn = true,
                offered = false,
                phoneToolsAllowed = false,
            ),
        )
    }
}
