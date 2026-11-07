package com.hermesvox

import kotlin.math.roundToInt

/**
 * LookControls — the being's scalar look controls as ONE table (#225).
 *
 * WHY A TABLE
 * -----------
 * A slider can lie in three ways that a screenshot never shows: it can write a pref the
 * renderer never reads, it can display a value it did not store, and its range can drift
 * from the one [VisualStyle] clamps to. Before this table each of the six sliders was wired
 * by hand with its own literal key and its own formatting lambda, so the only thing holding
 * the mapping together was a reader's eye. Now the table IS the wiring — Settings binds from
 * [ALL], so the slider, the value line and the reset all speak the same entry — and
 * [LookControlsTest] holds the table against the layout, the prefs and the renderer.
 *
 * WHAT A CONTROL OWNS
 * -------------------
 * One pref ([key], a [VisualStyle] constant's value), three layout views (the row, its
 * SeekBar and its value label, named because the screen resolves them), its bounds copied
 * from [VisualStyle] (never restated here), its shipped default (the identity — see
 * `VisualLookTest`), the range written the way the value is, and the one-line statement of
 * what it changes ([hint], which is [SettingsHints]' line for that row — one place, not two).
 *
 * NO DEFAULTS ARE CHANGED HERE. Every default is the value the renderer already shipped, so
 * an untouched install still draws exactly what it drew before #225.
 */
object LookControls {

    /** One slider. See the file header for what each field is for. */
    class Control(
        /** The pref this control writes — a VisualStyle key, never a private copy. */
        val key: String,
        /** The row, SeekBar and value label, by resource name. */
        val row: String,
        val seek: String,
        val value: String,
        /** The row title, as the user reads it. */
        val title: String,
        val min: Float,
        val max: Float,
        val step: Float,
        val default: Float,
        /** The range, written the same way the value is ("30–100 %"). */
        val range: String,
        /** How one value reads on the row. */
        val fmt: (Float) -> String
    ) {
        /** Slider positions between [min] and [max]; 0 would be a control that cannot move. */
        val steps: Int get() = ((max - min) / step).roundToInt()

        /** The value at a slider position. */
        fun at(progress: Int): Float = min + progress * step

        /** The slider position of a stored pref, snapped to the step and clamped. */
        fun progressOf(v: Float): Int = ((v.coerceIn(min, max) - min) / step).roundToInt()

        /** "100 % · 30–100 %": the current value, then the range it moves in. */
        fun labelAt(progress: Int): String = "${fmt(at(progress))} · $range"

        /** The same line for a raw stored value (clamped and snapped first). */
        fun label(v: Float): String = labelAt(progressOf(v))

        /** The one-line statement of what this control changes (#224's hint for its row). */
        val hint: String get() = SettingsHints.HINTS.getValue(row)

        /** True when the slider is already at the shipped default (the reset is a no-op). */
        fun isDefault(progress: Int): Boolean = progress == progressOf(default)
    }

    /**
     * Every slider in the Being section, in the order it is shown: the five #225 look
     * controls, then glow (which predates them and rides the same wiring so it gets the
     * same value/range line and the same one-tap reset).
     */
    val ALL: List<Control> = listOf(
        Control(
            VisualStyle.KEY_DENSITY, "row_visual_density", "set_seek_visual_density",
            "set_visual_density_val", "Density",
            VisualStyle.DENSITY_MIN, VisualStyle.DENSITY_MAX, VisualStyle.DENSITY_STEP,
            VisualStyle.DEFAULT_DENSITY, "30–100 %"
        ) { "${(it * 100).roundToInt()} %" },

        Control(
            VisualStyle.KEY_SPEED, "row_visual_speed", "set_seek_visual_speed",
            "set_visual_speed_val", "Speed",
            VisualStyle.SPEED_MIN, VisualStyle.SPEED_MAX, VisualStyle.SPEED_STEP,
            VisualStyle.DEFAULT_SPEED, "0.5–1.8 ×"
        ) { "%.1f×".format(it) },

        Control(
            VisualStyle.KEY_TRAIL, "row_visual_trail", "set_seek_visual_trail",
            "set_visual_trail_val", "Trail",
            VisualStyle.TRAIL_MIN, VisualStyle.TRAIL_MAX, VisualStyle.TRAIL_STEP,
            VisualStyle.DEFAULT_TRAIL, "0–90 %"
        ) { if (it <= 0f) "the category's" else "${(it * 100).roundToInt()} %" },

        Control(
            VisualStyle.KEY_HUE, "row_visual_hue", "set_seek_visual_hue",
            "set_visual_hue_val", "Hue",
            VisualStyle.HUE_MIN, VisualStyle.HUE_MAX, VisualStyle.HUE_STEP,
            VisualStyle.DEFAULT_HUE, "−180° … 180°"
        ) { "${it.roundToInt()}°" },

        Control(
            VisualStyle.KEY_ENERGY, "row_visual_energy", "set_seek_visual_energy",
            "set_visual_energy_val", "Motion energy",
            VisualStyle.ENERGY_MIN, VisualStyle.ENERGY_MAX, VisualStyle.ENERGY_STEP,
            VisualStyle.DEFAULT_ENERGY, "0.5–1.6 ×"
        ) { "%.1f×".format(it) },

        Control(
            VisualStyle.KEY_GLOW, "row_visual_glow", "set_seek_visual_glow",
            "set_visual_glow_val", "Glow / bloom",
            VisualStyle.GLOW_MIN, VisualStyle.GLOW_MAX, VisualStyle.GLOW_STEP,
            VisualStyle.DEFAULT_GLOW, "0.4–1.6 ×"
        ) { "%.1f×".format(it) }
    )

    /**
     * The five look controls #225 names — density, speed, trail, hue and motion scale
     * (`visual_energy`). Glow is the sixth slider and predates the issue; it is listed
     * separately so a test can hold the issue's set exactly.
     */
    val LOOK_KEYS: List<String> = listOf(
        VisualStyle.KEY_DENSITY, VisualStyle.KEY_SPEED, VisualStyle.KEY_TRAIL,
        VisualStyle.KEY_HUE, VisualStyle.KEY_ENERGY
    )

    /** The reset action's id name for a seek bar (`set_seek_visual_hue` -> `set_reset_visual_hue`). */
    fun resetName(seek: String): String = "set_reset_" + seek.removePrefix("set_seek_")
}
