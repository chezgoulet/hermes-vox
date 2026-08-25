---
id: ebe.build_repair
version: 1.0.0
kind: pipeline-running
model_role: repairer
inputs: [goal, source_entity, candidate, violations, protected_invariants]
output: repair_result
---
Repair only the listed violations while preserving all protected invariants. Return strict `repair_result` JSON; return `unable` with unresolved violation IDs when a bounded safe repair is impossible.
