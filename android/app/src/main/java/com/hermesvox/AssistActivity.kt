package com.hermesvox

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * #329: the digital-assistant entry point.
 *
 * Declared against `android.intent.action.ASSIST` + `android.intent.category.DEFAULT` — the
 * manifest component that makes an app eligible for `android.app.role.ASSISTANT`. It is a
 * translucent, no-history bridge on purpose: the assist gesture must land on the EXISTING
 * call/being screen, not on a second UI with its own state.
 *
 * The hand-off is a single Intent to MainActivity:
 *
 *  * `CLEAR_TOP | SINGLE_TOP` — if the call screen already exists in the task, it is brought
 *    forward and receives the intent in onNewIntent; Android does not build a second one.
 *  * `NEW_TASK` — the gesture can arrive with no task of ours in the foreground.
 *  * `MainActivity.EXTRA_ASSIST` — MainActivity runs [AssistRoute]: a live call is focused,
 *    an idle configured app opens ready to listen, a fresh install goes to onboarding.
 *
 * No service is started here and no microphone is opened here. What the gesture does is open
 * a screen; the call — and its existing foreground service — begins only through the normal
 * call start, which is the same code path the call button uses.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callScreen = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(MainActivity.EXTRA_ASSIST, true)
        startActivity(callScreen)
        finish()
        // A bridge that is seen at all is a flicker: there is nothing to animate to or from.
        overridePendingTransition(0, 0)
    }
}
