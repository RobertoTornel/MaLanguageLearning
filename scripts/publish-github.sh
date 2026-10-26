#!/usr/bin/env bash
# Create the GitHub repository (if needed) and push the current tree.
# Uses the credential helper already configured on this machine, so no token is echoed.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REPO_NAME="${REPO_NAME:-MaLanguageLearning}"
OWNER="${OWNER:-RobertoTornel}"
VISIBILITY="${VISIBILITY:-public}"

echo "==> Creating GitHub repository ${OWNER}/${REPO_NAME} (${VISIBILITY})"

if [[ ! -d .git ]]; then
  git init -q
  git checkout -q -b main 2>/dev/null || git branch -M main
fi

git add -A
if ! git rev-parse HEAD >/dev/null 2>&1; then
  git -c user.name="${GIT_AUTHOR_NAME:-RobertoTornel}" \
      -c user.email="${GIT_AUTHOR_EMAIL:-249965538+RobertoTornel@users.noreply.github.com}" \
      commit -q -m "MaLanguageLearning 0.1.0: learn languages by speaking item names"
fi

# Create the repo through the API; ignore "already exists".
HTTP=$(curl -sS -o /tmp/gh-create.json -w "%{http_code}" \
  -X POST https://api.github.com/user/repos \
  -H "Authorization: Bearer $(git credential fill <<< "protocol=https
host=github.com
" | grep '^password=' | cut -d= -f2-)" \
  -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"${REPO_NAME}\",\"private\":$([[ "$VISIBILITY" == "private" ]] && echo true || echo false),\"description\":\"Minecraft NeoForge mod: learn a language by speaking the names of the items you pick up.\",\"has_issues\":true,\"has_wiki\":false}")

echo "GitHub API: HTTP ${HTTP}"
head -c 300 /tmp/gh-create.json || true
echo

git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/${OWNER}/${REPO_NAME}.git"
git push -u origin main

echo "==> Pushed to https://github.com/${OWNER}/${REPO_NAME}"
