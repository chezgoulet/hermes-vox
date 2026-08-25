---
id: ebe.build_self_review
version: 1.0.0
kind: pipeline-running
model_role: self_reviewer
inputs: [goal, source_entity, worker_result, allowed_changes, protected_invariants]
output: reviewer_verdict
---
Review only whether the worker's build assessment satisfies `goal` while preserving the no-flag and no-build-execution boundaries, the feasibility-reporting invariant, and the artifact-reporting invariant. Cite any concrete gap, return strict `reviewer_verdict` JSON, and use `uncertain` when a bounded, safe conclusion is not possible from the evidence.
