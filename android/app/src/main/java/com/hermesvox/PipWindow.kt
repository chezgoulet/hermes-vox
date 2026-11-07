package com.hermesvox

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi

/**
 * PipWindow — the thin Android adapter for [Pip.Params].
 *
 * This file decides nothing: the aspect ratio, the action list, the labels and the
 * "no live call -> no window" rule all live in [Pip], where `PipTest` can hold them. What
 * is left here is the platform's own vocabulary — `PictureInPictureParams`, `Rational`,
 * `RemoteAction` — and the 31+ `setAutoEnterEnabled` flag that lets a swipe home shrink
 * the call instead of hiding it.
 *
 * Nothing in this object touches the audio path, the turn loop or the controller. A PiP
 * window is a window.
 */
@RequiresApi(Build.VERSION_CODES.O)
object PipWindow {

    /**
     * Build the platform description.
     *
     * [pending] maps an action to what a tap runs. An action with no handler is not
     * offered — the window never draws a control that would do nothing. The action icons
     * are [PipGlyph]'s own vector paths, tinted with the app's ink.
     *
     * [autoEnter] is only meaningful on 31+; below it the caller enters PiP through
     * `onUserLeaveHint` (see [Pip.enterOnLeave]), and the flag is not set at all.
     */
    fun params(
        context: Context,
        spec: Pip.Params,
        pending: Map<Pip.Action, PendingIntent>,
        autoEnter: Boolean = false,
    ): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(spec.aspectNumerator, spec.aspectDenominator))
        val actions = spec.actions.mapNotNull { action ->
            val intent = pending[action] ?: return@mapNotNull null
            RemoteAction(PipGlyph.icon(context, action), action.label, action.label, intent)
        }
        if (actions.isNotEmpty()) builder.setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }
}
