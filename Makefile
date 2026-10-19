# Ebitengine Boilerplate Makefile
# Cross-platform build orchestration.
#
# Design:
#   - Invoke the Python build driver (scripts/build.py) as the canonical
#     no-flag entry point. The Makefile is the human-facing convenience layer;
#     the Python driver is the authoritative target matrix + feasibility report.
#
# Targets:
#   build     Build every possible target from the current host.
#   clean     Remove build output.
#   test      Run the gate (Android build + unit tests).
#   verify    Build + verify the artifact matrix.
#   help      Show available targets.

TOOLS_DIR    := .tools
CACHE_DIR    := $(TOOLS_DIR)/cache
BIN_DIR      := $(TOOLS_DIR)/bin
RELEASE_DIR  := releases

.PHONY: all build clean test verify help tools
all: build

# ── Build ──────────────────────────────────────────────────────────────────────

build:
	@echo "▶ Building all possible targets (no-flag mode)..."
	python scripts/build.py

clean:
	@echo "▶ Cleaning build output..."
	-@if exist "$(RELEASE_DIR)" rmdir /s /q "$(RELEASE_DIR)"
	-@if exist "$(TOOLS_DIR)" rmdir /s /q "$(TOOLS_DIR)"
	@echo "✓ Clean complete."

test:
	@echo "▶ Running the gate (Android build + unit tests)..."
	bash scripts/gate.sh

verify: build
	@echo "▶ Verifying artifact matrix..."
	python scripts/build.py --verify

tools:
	@echo "▶ Ensuring a JDK is available..."
	java -version

help:
	@echo "Ebitengine Boilerplate — available targets:"
	@echo ""
	@echo "  build     Build every possible target from this host (no flags)"
	@echo "  clean     Remove releases/ and .tools/"
	@echo "  test      Run the gate (Android build + unit tests)"
	@echo "  verify    Build then verify the artifact matrix"
	@echo "  tools     Check a JDK is available"
