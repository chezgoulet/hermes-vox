---
plan_id: 2026-08-19-10-55-32_build-pipeline-implementation
title: Implement Ebitengine Build Pipeline
summary: Replace the Markdown-repair scaffold with a no-flag cross-platform build pipeline that compiles Ebitengine apps for all feasible local targets and validates produced artifacts.
status: past
created_at: 2026-08-19-10-55-32
---

# Implement Ebitengine Build Pipeline

Key: `[ ]` pending task, `[x]` completed task, `[?]` needs validation, `[-]` closed task

## 0. Corrective follow-through

- [x] Reconcile the host build contract, pipeline definition, prompts, README, TODO, and roadmap with the two-subsystem execution model.
- [x] Correct build-driver toolchain resolution, report serialization, artifact lookup, and target feasibility handling.
- [x] Stage the complete intentional build-pipeline implementation batch, including currently untracked source and documentation.
- [x] Record verification results in the journal and close or carry forward only verified work.

## 1. Replace scaffold with working build pipeline

- [x] Replace the existing Markdown-repair pipeline with a build pipeline whose primary job is compiling the Ebitengine app for all feasible local targets
- [x] Implement the no-flag build entry point that reports possible and impossible targets with reasons and then builds every possible target
- [x] Ensure build artifacts land in `releases/{goos}/{goarch}/{target}/latest[.exe]` (or `.apk` for Android)
- [x] Ensure the governance pipeline analyses declared build evidence without claiming to execute builds

## 2. Go scaffold

- [x] Create `go.mod` pinned to Go 1.26.4 with Ebitengine v2.9.9 as a public module dependency
- [x] Create `cmd/app/main.go` with a minimal placeholder Ebitengine view that renders
- [x] Generate `go.sum` via `go mod tidy`
- [x] Replace the placeholder with a self-contained, disposable drag-and-flick "Hello, world." demo
- [x] Use a stable application target directory in artifact paths instead of repeating the OS and architecture

## 3. Build tooling

- [x] Create `scripts/build.py` as the canonical build entry with a TARGETS dict and per-target feasibility report
- [x] Expand the preflight report with per-target local feasibility, output paths, prerequisites, and reasons
- [x] Create `scripts/build_orchestrator.py` that detects the host, constructs a BuildPlan, and delegates to `build.py` per target
- [x] Create `scripts/run_artifact.py` that locates and runs a built artifact, forwarding CLI arguments
- [x] Create `Makefile` that detects host GOOS/GOARCH via `uname`, pins GOCACHE/GOMODCACHE to `.tools/cache/`, and invokes the Python build scripts

## 4. Pipeline definition

- [x] Rewrite `pipeline.yaml` as optional governance scaffolding that never executes the primary build pipeline
- [x] Define stages: `worker` → `self_review` → `reviewer` (legal stage names)
- [x] Ensure the optional worker stage never claims to invoke the build entry point

## 5. Host prompts

- [x] Rewrite `prompts/worker.md` with `id: ebe.build_worker`, kind `pipeline-running`, model_role `worker`, and full required metadata
- [x] Rewrite `prompts/reviewer.md` with `id: ebe.build_reviewer`, validating artifact existence and target-matrix completeness
- [x] Rewrite `prompts/repair.md` with `id: ebe.build_repair`, diagnosing and fixing build failures
- [x] Ensure all prompts include required metadata: [id, version, kind, model_role, inputs, output]

## 6. VS Code entrypoints

- [x] Rewrite `.vscode/launch.json` to a single "Ebitengine Build" run config invoking the bootstrap with `run` subcommand
- [x] Modify `.vscode/tasks.json` to add the Main build task, a Deploy example task, and a Run Artifact task; keep existing diagnostic tasks; add `pipelineDefinition` input

## 7. Host documentation

- [x] Rewrite `AGENTS.md` opening to declare the build pipeline primary, deploy secondary, and Ollama optional
- [x] Fix `ROADMAP.md` Phase 1 to say "Add Ebitengine as public Go module dependency" (not submodule)
- [x] Update `TODO.md` to align with the expanded build-pipeline scope
- [x] Reframe agent and human documentation around the primary build pipeline, secondary deploy setup, and optional Ollama scaffolding

## 8. Housekeeping

- [x] Add `.tools/` to `.gitignore`
- [x] Remove or repurpose the `content/` directory that belonged to the old Markdown-repair scaffold

## 9. Verification

- [x] Run `preflight` via the bootstrap and confirm it passes
- [x] Execute `scripts/build.py --verify` and confirm all locally feasible targets build and artifacts appear in `releases/`
- [-] Optional governance validation is not a build-pipeline acceptance condition; an aggregate-evidence Ollama contract is deferred to TODO.
- [x] Refresh plan indexes to reflect completion
