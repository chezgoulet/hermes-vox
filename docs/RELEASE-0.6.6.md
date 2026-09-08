# Hermes Vox 0.6.6 — the ER tool-call crash

From the field log (14:22:49, gen=8 — the "go ahead and look it up" turn,
the first real tool call of an ER call): **NoSuchMethodError
`SendChannel.close$default` inside litertlm's own `sendMessageAsync` Flow
callback.** The crash killed the process mid-call.

## Root cause

`GemmaExpress.express` used litertlm's **Flow overload** of
`sendMessageAsync`. Its `onDone` closes the `callbackFlow` channel via
`SendChannel.close$default` — a DefaultImpls-style static compiled into the
litertlm AAR. In the R8-processed APK (minify on), that exact method
reference no longer resolves — so the first Gemma render that COMPLETED
(its flow closing) crashed the process. It only showed on tool-call turns
because that's the first time the ER narration render ran to completion
inside a live call.

## Fixes (belt and braces)

1. **GemmaExpress uses the MessageCallback overload** — `sendMessageAsync`
   with a `MessageCallback` builds no Flow/channel at all: `onMessage`
   appends text parts, `onDone` latches, `onError` records. litertlm's
   channel-close bytecode is never on the path.
2. **Proguard keeps the channels surface** litertlm's Flow path links
   against (`SendChannel` + `DefaultImpls` + `ProducerScope`), for any
   other litertlm code path that still uses it.

## Verification

Thelio gate on a clean clone. versionCode 110 / 0.6.6.
