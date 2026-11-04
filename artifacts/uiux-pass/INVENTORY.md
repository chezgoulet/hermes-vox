# UI/UX pass — inventory (written before any edit)

Every user-facing surface, one line on what is wrong with it. Ranked afterwards by what a
person feels: confusing grouping → clutter → stock-looking → polish.

## Surfaces

| # | Surface | Where | What is wrong |
|---|---|---|---|
| S1 | Settings home | `activity_settings.xml` `grp_list` | 12 rows in no order a person would follow: "Voice models" sits above the connection, "Appearance & Layout" and "Being" are two rows for one question ("how does it look?"), "Transcription (STT)" is split from "Hearing" though both answer "how does it hear me?" |
| S2 | Settings › Appearance & Layout | `sec_appearance` | Three rows (theme, layout, screen-on) cut off from the being they colour; you choose Light here and cannot see what it does to the being until you go back and open "Being" |
| S3 | Settings › Being | `sec_visuals` | The look of the being lives apart from the theme it is drawn on; also holds the sound cues, which are not a look — "turn the beeps off" is not findable under "Being" |
| S4 | Settings › Hearing | `sec_speech` | Owns the mic but not the recogniser, so "which speech recogniser" needs a second section (Transcription) to be guessed |
| S5 | Settings › Transcription (STT) | `sec_stt` | Jargon title ("STT"); a separate section for five rows that belong to hearing; its own restore row duplicates Hearing's |
| S6 | Settings › Voice models | `sec_models` | A whole section holding one row ("Open the model store") plus a restore: two taps to reach the store, one of them meaningless |
| S7 | Settings › Voice | `sec_tts` | Says nothing about sound cues, the other half of "what it sounds like" |
| S8 | Settings › Entity & Connection | `sec_entity` | "New conversation (clear context)" is a conversation action living in a settings section (also a command); acceptable, left — see second pass |
| S9 | Settings › Presence | `sec_presence` | Behind the Alpha gate (untouched by this pass); copy is long but each row is real — left |
| S10 | Settings › About & Diagnostics | `sec_about` | Fine in place; last on the home list, as it should be |
| S11 | Settings › section title | `SettingsActivity.showSection` | The title map duplicates the home row labels by hand, so a rename can disagree with itself (it did: home "Be the phone's assistant" vs title "The assistant role") |
| S12 | Settings pickers (≈20) | `AlertDialog.setSingleChoiceItems` | Stock AppCompat dialog: white Material card on the OLED black, system font, uppercase stock buttons — nothing of the app's ground, corners or type |
| C1 | Commands sheet | `CommandsSheet.kt` | Every card command carries "opens a card" — a helper line saying what the tap will do |
| C2 | Commands sheet | `CommandCatalog.kt` | Seven commands spread over four headers (two headers hold one row each); `/help` is listed inside the very screen that already is the help |
| C3 | Commands sheet | rows | Three lines per row (name, effect, cost) even when the cost is trivia ("Stays down if the gateway does not answer") |
| C4 | Commands sheet | rows | Every row looks the same: nothing says what kind of thing a command does, though the being already has a six-verb grammar that does exactly that |
| C5 | Commands sheet | entry | No window transition (Alpha panel slides; this pops) |
| M1 | Model switcher | `MainActivity.showModelChooser` | Stock single-choice AlertDialog: a flat list of every model of every provider |
| M2 | Model switcher | rows | Rows are raw `"* provider - model-id (source)"` strings; no size, no purpose, no current mark you can read |
| M3 | Model switcher | title | "Gateway models (choose your agent's brain)" — parenthetical, cute, wraps on a phone |
| M4 | Model switcher | states | Loading is a status-pill flicker, failure a toast, empty a toast; none of them is in the place you are looking |
| M5 | Model switcher | outcome | "Model set -> prov/id" toast: an arrow and two identifiers |
| H1 | Health card | `showHealthCard` | "Status: ok / Version: x" in a stock dialog; failure says "(debug: check network + address)" |
| H2 | Help card | `showHelpCard` | Duplicates the commands sheet in a stock dialog |
| H3 | Compress card | `compressContext` | Stock dialog (content fine) |
| P1 | Profile switcher | `ProfileDialogs.showSwitcher` | Stock `setItems` list with "● " / "   " text-indent marks and "+ Add a profile" as a fake row |
| P2 | Profile editor / add / edit pickers | `ProfileDialogs` | Stock dialogs |
| A1 | Alpha panel | `AlphaPanel.kt` | Custom, on-ground, slides in — the reference the other sheets should match; it repeats the header code the other sheets repeat |
| SH1 | Share sheet | `ShareSheet.kt` | Custom; header code duplicated a third time |
| F1 | File offer sheet | `FileOfferSheet.kt` | Custom; header duplicated a fourth time |
| O1 | Onboarding | `activity_onboarding.xml` | Custom and on-ground; "Skip for now?" confirm is stock |
| W1 | Wake enrolment | `activity_wake_enrol.xml` | On-ground; "Delete all recordings?" is stock |
| VM1 | Voice model store | `ModelsActivity` | On-ground, grouped by role; licence dialog stock |
| MS0 | What's-new card | `activity_main.xml` `update_banner` | **Found on the emulator, worst of all:** a long note pushes the being, the call controls and the card's own "GOT IT" off the screen with nothing to scroll — the app is unusable on the first launch after an upgrade; its action buttons are stock grey `Button`s |
| MS1 | Main screen | `activity_main.xml` | Coherent; glyph buttons (⚙ ✆ /) have content descriptions; not changed |
| T1 | Toasts | app-wide | Used for outcomes that have a home on screen (model set, catalog unreachable) |

## Ranking (worst first, by what a person feels)

0. **Broken** — MS0 (the what's-new card swallows the screen).
1. **Grouping** — S1–S7, S11 (settings re-grouped; Being + Appearance one section; hearing one section; the store one tap).
2. **Clutter** — C1–C3 (commands: no "opens a card", two groups, risk lines only where something is lost, `/help` not listed in itself).
3. **Stock-looking** — M1–M5 (the model switcher becomes ours: provider → category fan-out), S12/H1–H3/P1–P2/O1/W1/VM1 (one dialog theme for every AlertDialog in the app).
4. **Polish** — C4–C5 (each command drawn with its verb; sheets share one scaffold and one entry motion), A1/SH1/F1 (the repeated header extracted).

Left for a second pass: S8, S9, T1 beyond the model switcher, MS1, and the profile switcher's
list becoming a sheet (P1 — it gets the dialog theme now, not a rebuild).
