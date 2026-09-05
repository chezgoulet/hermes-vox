# turnbench baseline harness

Simulates the deployed Hermes Vox endpointing rules against Sesame
TurnBench (github.com/SesameAILabs/turnbench). Requires the turnbench repo
env (`uv sync` there) + silero_vad.onnx (models-store/silero-vad.zip on the
build host; also HF snakers4/silero-vad). See README.md for commands + the
operating-point citations.

Real dev set: https://huggingface.co/datasets/mundo-ai/turn-benchmark-dev
(gated; accept terms with a human HF account first).
