#!/usr/bin/env bash
set -euo pipefail

trap 'printf "%s\n" "bootstrap: interrupted" >&2; exit 130' INT

host_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$host_root"

printf '%s\n' 'bootstrap: checking Python and declared host-local dependencies'
command -v python3 >/dev/null 2>&1 || { printf '%s\n' 'bootstrap: python3 is unavailable.' >&2; exit 1; }

python3 agentic-pipelines/scripts/bootstrap_pipeline_environment.py --host-root . --requirements requirements-pipeline.txt --requirements agentic-pipelines/requirements.txt --check-module yaml
printf '%s\n' 'bootstrap: dependencies ready; starting host pipeline'
exec python3 agentic-pipelines/scripts/pipeline.py "$@"
