---
id: ebe.build_worker
version: 1.0.0
kind: pipeline-running
model_role: worker
inputs: [goal, source_entity, allowed_changes, protected_invariants]
output: worker_result
---
Analyse the supplied build-contract and evidence inputs for `goal`; do not claim to execute builds or invoke tools. Return strict `worker_result` JSON with the declared target matrix, reported outcomes, and any evidence gaps. Preserve the no-flag and no-build-execution boundaries, report every unsupported or unproven target with its reason, and return `unable` when the evidence cannot support a bounded assessment.
