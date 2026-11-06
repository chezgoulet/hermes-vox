package com.hermesvox

/**
 * CommandGate — the one gate the app hands every surface that lists or answers a command
 * (#402, #418, #437).
 *
 * Three commands can be absent, each behind its own switch, and nothing else in the
 * catalogue is ever gated:
 *
 *  * `/profiles` — the Alpha **Switching profiles** set ([AlphaGate.FeatureSet.PROFILES]);
 *  * `/sessions` — the Alpha **Sessions** set ([AlphaGate.FeatureSet.SESSIONS]);
 *  * `/diagnostics` — the Diagnostic-summary switch in Settings › About ([DiagnosticSummary]).
 *
 * A gated command with its switch off is not a hidden row: it is a name no build answers to,
 * so the sheet, /help and a typed word all treat it as unknown. Because they ask this one
 * object, there is one way a row is absent rather than three.
 *
 * It reads the switches **when asked**, not when the sheet is built: someone who turns
 * Sessions on in Settings and comes back finds the row without restarting the app. (Before
 * #501 the sheet captured `/profiles`' answer once, at the first opening, so a set turned on
 * later appeared everywhere except in the sheet until the process died.)
 *
 * Pure JVM: the reader and the diagnostic store are fakes off-device ([CommandGateTest]).
 */
object CommandGate {

    /** The gate for this moment, for [reader] (Alpha) and [diagnostics] (the summary switch). */
    fun of(reader: AlphaGate.Reader, diagnostics: DiagnosticSummary.Store): CommandCatalog.Gate =
        CommandCatalog.Gate { c ->
            when (c.name) {
                "/profiles" -> AlphaGate.allows(reader, AlphaGate.FeatureSet.PROFILES)
                "/sessions" -> AlphaGate.allows(reader, AlphaGate.FeatureSet.SESSIONS)
                "/diagnostics" -> DiagnosticSummary.on(diagnostics)
                else -> true
            }
        }
}
