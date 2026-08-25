$PipelineArgs = $args

$ErrorActionPreference = 'Stop'
$HostRoot = Split-Path -Parent $PSScriptRoot

try {
    Set-Location $HostRoot
    Write-Host 'bootstrap: checking Python and declared host-local dependencies'
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
        throw 'python is unavailable. Install a supported Python interpreter before running the host pipeline.'
    }

    & python agentic-pipelines/scripts/bootstrap_pipeline_environment.py --host-root . --requirements requirements-pipeline.txt --requirements agentic-pipelines/requirements.txt --check-module yaml
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host 'bootstrap: dependencies ready; starting host pipeline'
    & python agentic-pipelines/scripts/pipeline.py @PipelineArgs
    exit $LASTEXITCODE
}
catch [System.Management.Automation.PipelineStoppedException] {
    Write-Error 'bootstrap: interrupted'
    exit 130
}
catch {
    Write-Error "bootstrap: $($_.Exception.Message)"
    exit 1
}
