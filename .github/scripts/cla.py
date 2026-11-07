#!/usr/bin/env python3
"""The CLA check for pull requests, and the acceptance comment that clears it.

Two events land here:

  pull_request_target — a PR was opened or updated. If the author's handle is in
  CLA-SIGNATURES.md the commit status is green; otherwise the job posts the acceptance
  text once, labels the PR `cla-pending`, and sets the status red with a reason.

  issue_comment — someone commented. If the commenter is the PR author and the body
  carries the acceptance line, their row is appended to CLA-SIGNATURES.md on the
  default branch and the PR's status turns green.

The acceptance is deliberately literal. A sentence that *means* agreement is not the same
as a sentence that records it, and this is the one place where being generous is wrong.

Everything is driven by environment variables so the logic can be exercised without GitHub:

  CLA_EVENT        pull_request_target | issue_comment | self-test
  CLA_REPO         owner/name
  CLA_PR           the pull request number
  CLA_PR_AUTHOR    login of the PR author
  CLA_HEAD_SHA     head commit of the PR
  CLA_COMMENT_BODY raw comment body (issue_comment only)
  CLA_COMMENT_AUTHOR login of the commenter (issue_comment only)
  GH_TOKEN         a token that may write statuses, comments, labels and the signature file

Run `CLA_EVENT=self-test python3 cla.py` to check the parsing rules.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

SIGNATURES = "CLA-SIGNATURES.md"
MARKER = "<!-- cla-check -->"
LABEL = "cla-pending"

ACCEPT = re.compile(
    r"i\s+have\s+read\s+the\s+CLA\s+at\s+CLA\.md\s+and\s+i\s+agree\s+to\s+it",
    re.IGNORECASE,
)


def gh(*args: str, body: str | None = None) -> str:
    cmd = ["gh", *args]
    if body is not None:
        cmd += ["--body-file", "-"]
    out = subprocess.run(cmd, input=body, capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"gh {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout.strip()


def signed_handles(text: str) -> set[str]:
    """Handles already recorded in the signature index."""
    handles = set()
    for line in text.splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 4 or set(cells[1]) <= {"-"}:
            continue
        handle = cells[2].lstrip("@").strip()
        if handle and handle.lower() != "github handle" and re.sub(r"[\s—–-]", "", handle):
            handles.add(handle.lower())
    return handles


def accepts(body: str) -> bool:
    """The acceptance line, and only the acceptance line."""
    flat = re.sub(r"\s+", " ", body or "")
    return bool(ACCEPT.search(flat))


def status(state: str, description: str, sha: str, repo: str) -> None:
    gh(
        "api", f"repos/{repo}/statuses/{sha}", "-X", "POST",
        "-f", f"state={state}",
        "-f", "context=CLA",
        "-f", f"description={description[:140]}",
    )


def comment_once(repo: str, pr: str, body: str) -> None:
    # Do not ask twice: skip when the bot has already commented, and also when a maintainer
    # has already written the acceptance text by hand on this pull request.
    existing = gh("api", f"repos/{repo}/issues/{pr}/comments", "--paginate",
                  "-q", f'.[] | select((.body | contains("{MARKER}")) or (.body | test("I have read the CLA at CLA.md"; "i"))) | .id')
    if existing:
        return
    gh("pr", "comment", pr, "-R", repo, body=f"{MARKER}\n{body}")


def signature_text(repo: str) -> str:
    return gh("api", f"repos/{repo}/contents/{SIGNATURES}",
              "-H", "Accept: application/vnd.github.raw")


def append_signature(repo: str, name: str, handle: str, email: str, pr: str) -> None:
    """Add a row to the index on the default branch, with a commit that says why."""
    import base64
    import json
    import datetime

    meta = json.loads(gh("api", f"repos/{repo}/contents/{SIGNATURES}"))
    current = base64.b64decode(meta["content"]).decode()
    row = f"| {datetime.date.today().isoformat()} | {name or handle} | @{handle} | #{pr} |"
    lines = current.rstrip("\n").split("\n")
    # The table's last row is the placeholder until the first signature exists.
    if lines and set(lines[-1].strip("| ").replace("-", "").replace("|", "")) == set():
        lines = lines[:-1]
    lines.append(row)
    updated = "\n".join(lines) + "\n"
    gh(
        "api", f"repos/{repo}/contents/{SIGNATURES}", "-X", "PUT",
        "-f", f"message=CLA: {handle} accepted the agreement on #{pr}",
        "-f", f"content={base64.b64encode(updated.encode()).decode()}",
        "-f", f"sha={meta['sha']}",
        "-f", "branch=" + gh("api", f"repos/{repo}", "-q", ".default_branch"),
    )


def main() -> int:
    event = os.environ.get("CLA_EVENT", "")
    repo = os.environ.get("CLA_REPO", "")
    pr = os.environ.get("CLA_PR", "")

    if event == "self-test":
        assert accepts("I have read the CLA at CLA.md and I agree to it.")
        assert accepts("i have read the cla at CLA.md and i agree to it")
        assert accepts("I have read the CLA at CLA.md and I agree to it.\nName: Ada\nGitHub handle: ada\nEmail: a@b.c")
        assert not accepts("I agree to the CLA")
        assert not accepts("I have read CLA.md")
        assert not accepts("I do not agree to the CLA at CLA.md and I agree to it")
        table = (
            "| date | name | GitHub handle | pull request |\n"
            "|---|---|---|---|\n"
            "| 2026-11-06 | Ada | @ada | #12 |\n"
        )
        assert signed_handles(table) == {"ada"}
        assert signed_handles("| — | — | — | — |") == set()
        print("self-test: OK")
        return 0

    if event == "pull_request_target":
        author = os.environ.get("CLA_PR_AUTHOR", "")
        sha = os.environ["CLA_HEAD_SHA"]
        if author.lower().endswith("[bot]"):
            status("success", "automation", sha, repo)
            return 0
        if author.lower() in signed_handles(signature_text(repo)):
            status("success", f"CLA signed by @{author}", sha, repo)
            return 0
        comment_once(repo, pr, (
            "Thank you for this. Before it can merge, the project needs the contributor licence "
            "agreement on file — it is short, and it keeps your copyright.\n\n"
            "Post this on this thread:\n\n"
            "> I have read the CLA at CLA.md and I agree to it.\n"
            "> Name:\n> GitHub handle:\n> Email:\n\n"
            "The community edition stays GPL-3.0; the agreement simply lets the project also offer "
            "a commercial edition later. `CLA.md` has the whole text."
        ))
        gh("api", f"repos/{repo}/issues/{pr}/labels", "-X", "POST", "-f", f"labels[]={LABEL}")
        status("failure", "the CLA needs accepting — see the comment on this PR", sha, repo)
        return 0

    if event == "issue_comment":
        body = os.environ.get("CLA_COMMENT_BODY", "")
        who = os.environ.get("CLA_COMMENT_AUTHOR", "")
        if not accepts(body):
            return 0                      # an ordinary comment; the status is unchanged
        meta = gh("pr", "view", pr, "-R", repo, "--json", "author,headRefOid")
        import json
        info = json.loads(meta)
        if info["author"]["login"].lower() != who.lower():
            gh("pr", "comment", pr, "-R", repo, body=(
                f"@{who} — thanks, but the agreement has to come from the person who wrote the "
                f"contribution (@{info['author']['login']}). A maintainer can record it if that "
                "person has agreed in writing elsewhere."
            ))
            return 0
        name = ""
        for line in body.splitlines():
            if line.lower().startswith("name:"):
                name = line.split(":", 1)[1].strip()
        append_signature(repo, name, who, "", pr)
        status("success", f"CLA signed by @{who}", info["headRefOid"], repo)
        gh("api", f"repos/{repo}/issues/{pr}/labels/{LABEL}", "-X", "DELETE")
        gh("pr", "comment", pr, "-R", repo, body=(
            f"Recorded — thank you, @{who}. `{SIGNATURES}` now carries your entry, and the check "
            "on this pull request has turned green."
        ))
        return 0

    print(f"unknown CLA_EVENT {event!r}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
