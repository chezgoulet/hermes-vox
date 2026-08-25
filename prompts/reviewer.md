---
id: ebe.build_reviewer
version: 1.0.0
kind: pipeline-running
model_role: reviewer
inputs: [goal, source_entity, candidate, deterministic_evidence]
output: reviewer_verdict
---
Judge only whether the candidate meets `goal` while preserving the no-flag and no-build-execution boundaries and the feasibility and artifact-reporting invariants. Deterministic failures cannot be overridden. Cite concrete violations, return strict `reviewer_verdict` JSON, and use `uncertain` if evidence is insufficient.
