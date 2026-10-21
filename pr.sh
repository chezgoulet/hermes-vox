#!/bin/bash
set -e
cd /home/c/vox-loop
git log --oneline -1
git push -q -u origin docs/the-loop 2>/dev/null || true
PR=$(gh pr create --repo chezgoulet/hermes-vox --base testing --head docs/the-loop \
  --title "docs: the 0.9.1 goal is the closed loop (D24)" --body-file /home/c/vox-loop/commit_msg.txt | grep -oE "[0-9]+$")
echo "PR=$PR"
for i in $(seq 1 24); do
  s=$(gh pr view "$PR" --repo chezgoulet/hermes-vox --json statusCheckRollup -q "[.statusCheckRollup[]? | \"\(.name)=\(.status)/\(.conclusion // \"-\")\"] | join(\"  \")")
  echo "try $i: $s"
  p=$(gh pr view "$PR" --repo chezgoulet/hermes-vox --json statusCheckRollup -q "[.statusCheckRollup[]? | select(.status!=\"COMPLETED\")] | length")
  t=$(gh pr view "$PR" --repo chezgoulet/hermes-vox --json statusCheckRollup -q "[.statusCheckRollup[]?] | length")
  if [ "$p" = "0" ] && [ "$t" != "0" ]; then break; fi
  sleep 20
done
bad=$(gh pr view "$PR" --repo chezgoulet/hermes-vox --json statusCheckRollup --jq "[.statusCheckRollup[]? | select(.status != \"COMPLETED\" or (.conclusion | test(\"^(SUCCESS|NEUTRAL|SKIPPED)$\") | not))] | length")
echo "non-green=$bad"
if [ "$bad" = "0" ]; then gh pr merge "$PR" --repo chezgoulet/hermes-vox --merge --delete-branch; fi
git fetch origin --quiet
git log --oneline -2 origin/testing
