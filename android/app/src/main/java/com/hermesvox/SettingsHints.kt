package com.hermesvox

/**
 * SettingsHints — one line under every Settings row saying what it changes (#224).
 *
 * "A toggle nobody can predict is a bug in the label, not in the user." The table is keyed by
 * the row's resource name so a test can hold it against the layout: every row has a line,
 * and no line names a row that no longer exists. The exempt rows already say what they do
 * (the restore rows, a disclosure chip, a row that carries its own description).
 *
 * Pure JVM; SettingsActivity renders each line under the row's title.
 */
object SettingsHints {

    /** The longest a hint may be: one line on a phone. */
    const val MAX_CHARS = 72

    val EXEMPT = setOf(
        "row_restore_entity", "row_mic_reset", "row_stt_reset", "row_tts_reset",
        "row_restore_presence", "row_restore_models", "row_restore_appearance",
        "row_restore_visuals", "row_restore_about",
        "row_mic_advanced",      // the disclosure chip itself
        "row_visual_cycle_all",  // carries its own description line
    )

    val HINTS: Map<String, String> = linkedMapOf(
        // home
        "row_mode" to "Realtime is the agent alone; Enhanced adds Presence on the phone",
        // Entity & Connection
        "row_test_conn" to "Checks the address and key reach your Hermes gateway",
        "row_entity" to "Where your Hermes gateway is, and the key it expects",
        "row_reset" to "Starts a fresh conversation; ends a call that is live",
        "row_app_skill" to "So it can answer \"where is that setting?\" from the app's own notes",
        // Hearing
        "row_bargein" to "Lets you talk over a reply to interrupt it",
        "row_mic_aec" to "Removes the phone's own voice from what the mic hears",
        "row_mic_ns" to "Filters steady background noise; can dull quiet speech",
        "row_mic_partial" to "May start the agent at a pause that sounds final",
        "row_mic_voicecall" to "Routes audio like a phone call, so echo cancelling works better",
        "row_mic_vad" to "How loud speech must be to count; raise it in a noisy room",
        "row_mic_silence" to "How long a pause ends your turn",
        "row_mic_early" to "The shorter pause that may end a turn early",
        "row_mic_min_speech" to "Sounds shorter than this are ignored as noise",
        "row_mic_max" to "A soft limit: a long turn still ends only at a pause",
        "row_mic_barge_rms" to "How loud you must be to interrupt a reply",
        "row_mic_barge_grace" to "How long after a reply starts before an interruption counts",
        "row_mic_barge_level" to "Interrupt on loudness alone after this long; 0 is off",
        // Transcription
        "row_stt" to "Which recogniser turns your speech into text",
        "row_stt_model" to "The on-device model that recogniser uses",
        "row_stt_threads" to "Processor cores for speech in and out; Auto suits most phones",
        "row_test_stt_conn" to "Checks that your own speech-to-text server answers",
        // Voice
        "row_tts" to "Which engine speaks the agent's replies",
        "row_voice" to "The speaking register: natural, bright or deep",
        "row_tts_speaker" to "Which of the engine's voices speaks",
        "row_speak" to "Off shows replies as text only",
        // Presence
        "row_er_presence" to "Short asides while the agent works; off keeps Presence quiet",
        "row_er_semantic_barge" to "\"Take your time\" lets the agent keep working; \"stop\" cancels",
        "row_er_hears" to "Presence reads your tone of voice, on the phone only",
        "row_er_voice" to "Silent, sounds (mm, a breath) or short spoken lines",
        "row_er_density" to "How often Presence speaks during a long task",
        "row_er_echo" to "Ignores the mic briefly after Presence speaks, so it can't hear itself",
        "row_vox" to "Fetches the agent's own description of its voice from the gateway",
        "row_vox_view" to "Shows what Presence reads about the agent's voice",
        // Voice models
        "row_models" to "Download or remove the voice models",
        // Appearance & Layout
        "row_theme" to "Light, dark, or follow the phone",
        "row_layout" to "The being, or a readable transcript",
        "row_keep_screen_on" to "Stops the screen sleeping while a call is live",
        // Being
        "row_visual_category" to "The being's family: its colour, light and motion",
        "row_visual_energy" to "How fast and lively the being moves",
        "row_visual_glow" to "How much light the being gives off",
        "row_visual_density" to "How many particles make up the being",
        "row_visual_speed" to "How fast its drift, shimmer and breath run",
        "row_visual_trail" to "Light trails behind each particle",
        "row_visual_hue" to "Turns every colour around the wheel",
        "row_speak_shape" to "The shape it takes while it speaks",
        "row_listen_shape" to "The shape it takes while it listens",
        "row_think_shape" to "The shape it takes while the agent works",
        "row_particle_theme" to "The shape it rests in between turns",
        "row_particle_cycle" to "Drifts through resting shapes while idle",
        "row_sound_cues" to "Short sounds for listening, working, ready and failed",
        "row_sound_cue_level" to "How loud cues are, relative to the media volume",
        "row_sound_cues_silent" to "Plays cues even with the ringer silenced",
        "row_cue_scope" to "Cues and Presence share one channel; pick when cues play",
        "row_cue_ping" to "A quiet note every few seconds while the agent works",
        "row_cue_idle" to "One sound after a long silence on a call, in case it stalled",
        // About & Diagnostics
        "row_devconsole" to "Shows the raw stream under the being",
        "row_logtranscripts" to "Writes what you said into the log; off by default",
        "row_debuglog" to "A more detailed log file, for reporting a bug",
        "row_debug" to "View, copy or share the log",
    )
}
