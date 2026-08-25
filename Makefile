# Ebitengine Boilerplate Makefile
# Cross-platform build orchestration.
#
# Design:
#   - Detect host GOOS/GOARCH via uname/Go runtime (no flags).
#   - Pin GOCACHE / GOMODCACHE under .tools/cache so CI and dev stay in sync.
#   - Invoke the Python build driver (scripts/build.py) as the canonical
#     no-flag entry point. The Makefile is the human-facing convenience layer;
#     the Python driver is the authoritative target matrix + feasibility report.
#
# Targets:
#   build     Build every possible target from the current host.
#   clean     Remove build output.
#   test      Run Go unit tests.
#   verify    Build + verify the artifact matrix.
#   help      Show available targets.

GO_TOOLCHAIN ?= go1.26.4
GO           := $(GO_TOOLCHAIN)

TOOLS_DIR    := .tools
CACHE_DIR    := $(TOOLS_DIR)/cache
BIN_DIR      := $(TOOLS_DIR)/bin
RELEASE_DIR  := releases

export GOCACHE     := $(abspath $(CACHE_DIR)/go-build)
export GOMODCACHE  := $(abspath $(CACHE_DIR)/go-mod)
export GOFLAGS     := -mod=mod
export GOENV       := $(abspath $(TOOLS_DIR)/goenv)

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
	@echo "▶ Running Go tests..."
	$(GO) test ./...

verify: build
	@echo "▶ Verifying artifact matrix..."
	python scripts/build.py --verify

tools:
	@echo "▶ Ensuring toolchain $(GO_TOOLCHAIN) is available..."
	$(GO) version

help:
	@echo "Ebitengine Boilerplate — available targets:"
	@echo ""
	@echo "  build     Build every possible target from this host (no flags)"
	@echo "  clean     Remove releases/ and .tools/"
	@echo "  test      Run Go unit tests"
	@echo "  verify    Build then verify the artifact matrix"
	@echo "  tools     Check the Go toolchain is available"
	@echo ""
	@echo "Environment (auto-set):"
	@echo "  GOCACHE     = $(GOCACHE)"
	@echo "  GOMODCACHE  = $(GOMODCACHE)"
	@echo "  GO_TOOLCHAIN = $(GO_TOOLCHAIN)"
