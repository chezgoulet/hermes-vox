# Hermes Vox 0.9.1.4 — out loud again

A one-fix patch on 0.9.1.3.

## The call was speaking into the earpiece

Voice output plays on the **communication** audio strategy (`USAGE_VOICE_COMMUNICATION`, chosen so
the platform's echo canceller can work on the microphone during a call). With no headset attached,
Android's default device for that strategy is the **earpiece** — and the app, finding no headset to
route to, left the route alone. A hands-free assistant was therefore talking quietly at the phone
instead of out loud.

Measured on a Pixel 7 during a live call, before and after:

```
before:  Active communication device: type:earpiece
         event=comm-route action=none api=37 available=TYPE_1+BUILTIN_SPEAKER

after:   Active communication device: type:speaker
         event=comm-route action=set ok=true type=BUILTIN_SPEAKER previous=TYPE_1 reason=headset-absent
```

The route now falls back to the built-in speaker when no headset is attached, on both the
Android 12+ path and the older `speakerphone` path. **A paired headset still wins**, and the route
is cleared at hang-up exactly as before, so nothing is left behind for the next app.

## What this does not claim

It does not claim the change was new. The voice usage was already the communication strategy before
this week, so if the call used to be loud, either a headset was attached or an earlier change moved
the route. What is fixed, either way, is that a hands-free call is quiet without one.

Nothing else changed in this release.
