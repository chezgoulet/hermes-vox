package com.hermesvox

/**
 * CommandGate — the one gate the app hands every surface that lists or answers a command
 * (#402, #418, #437).
 *
 * One command can be absent, behind its own switch, and nothing else in the catalogue is
 * ever gated:
 *
 *  * `/diagnostics` — the Diagnostic-summary switch in Settings › About ([DiagnosticSummary]).
 *
 * A gated command with its switch off is not a hidden row: it is a name no build answers to,
 * so the sheet, /help and a typed word all treat it as unknown. Because they ask this one
 * object, there is one way a row is absent.
 *
 * `/profiles` and `/sessions` were gated here until they left Alpha (#436, #437, D39); their
 * leaves are recorded in `docs/DECISIONS.md`.
 *
 * It reads the switch **when asked**, not when the sheet is built: someone who turns the
 * diagnostic summary on in Settings and comes back finds the row without restarting the app.
 * (Before #501 the sheet captured `/profiles`' answer once, at the first opening, so a switch
 * turned on later appeared everywhere except in the sheet until the process died.)
 *
 * Pure JVM: the diagnostic store is a fake off-device ([CommandGateTest]).
 */
object CommandGate {

    /** The gate for this moment, for [diagnostics] (the summary switch). */
    fun of(diagnostics: DiagnosticSummary.Store): CommandCatalog.Gate =
        CommandCatalog.Gate { c ->
            when (c.name) {
                "/diagnostics" -> DiagnosticSummary.on(diagnostics)
                else -> true
            }
        }
}
