package com.hermesvox

/** Settings → Appearance → "Keep screen on during calls". While a call is open the
 *  call window holds FLAG_KEEP_SCREEN_ON, so the display never times out; the power
 *  button still turns it off. On by default since 0.8 — a voice call you can watch
 *  should not go dark mid-sentence. */
const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
const val KEEP_SCREEN_ON_DEFAULT = true
