# Watch list — prior art followed, not scheduled

Moved here from the tracker on 2026-11-11, when the "watch" issues closed (`#237`, `#292`, `#293`;
`#238` closed as acted on). A watch item is not work: it has no release, no owner and no done
condition, and keeping it as an open issue hid the real backlog. Each entry keeps its revisit
trigger. When a trigger fires, open an issue that names the release it serves.

| Item | What it is | Licence | Why not now | Revisit when |
|---|---|---|---|---|
| **Ming-3.0-tts 0.5B** (`inclusionAI`) — was `#237` | Qwen2.5-0.5B backbone + DiT head, 44.1 kHz, seven languages, zero-shot cloning, 14 inline paralinguistic tags | MIT | 2.96 GB of safetensors; PyTorch only; no ONNX for the backbone; sherpa-onnx has no path; would sit beside Gemma | an ONNX/GGUF export or sherpa-onnx support; a quantised build small enough to co-reside; time-to-first-audio measured on a phone |
| **KittenTTS** (`KittenML`) — was `#292` | ONNX minis (15–80 M, 41–80 MB, eight fixed voices, English) and a 1.7 B 1-bit cloner (20 languages, tags) | Apache-2.0 | no Android packaging; minis English-only; the 1.7 B cannot co-reside with Gemma; a second runtime, not a model swap | an Android/ONNX example or a sherpa-onnx recipe; a voice bake-off scheduled for another reason |
| **Phonon** (`gradium`) — was `#293` | 31 M streaming TTS (Pocket TTS + Mimi), 64.5 MB q8 GGUF, 80 ms frames, Kotlin/JNI and Snapdragon QNN paths | 31 M English: Apache-2.0; 40 M multilingual: CC BY-NC (not shippable) | build-from-source Android; Maven package unpublished; QNN needs Snapdragon 8-class with no CPU fallback | the `ai.gradium:ptts` artifact ships; an Apache multilingual model; a bake-off |
| **VoiceManager** (`TacoTakumi`) — was `#238` | A local speech server carrying streaming STT and TTS over one WebSocket | MIT | Not a dependency. Its protocol was read and the contracts adopted (`docs/NOTES-voicemanager.md`): three-frame utterances → `#229`, cancel lifecycle → `#240` (closed) | nothing pending |

One idea worth taking without any weights: Ming's **inline tag vocabulary** as a model-agnostic way
to write delivery intent, beside today's mood → speed/pause mapping (`VoiceMood`).
