#!/usr/bin/env bash
# Publish MaLanguageLearning to Modrinth.
#
# Required environment:
#   MODRINTH_TOKEN   - a Modrinth personal access token (Settings -> PATs)
#
# Optional:
#   VERSION          - version number (default: read from gradle.properties)
#   PROJECT_ID       - Modrinth project id/slug (default: malanguagelearning)
#   CHANGELOG        - changelog text (default: read from CHANGELOG.md if present)
#   GAME_VERSIONS    - comma separated MC versions (default: 26.3)
#   LOADERS          - comma separated loaders (default: neoforge)
#   RELEASE_TYPE     - release | beta | alpha (default: beta)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-$(grep -E '^mod_version=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')}"
PROJECT_ID="${PROJECT_ID:-malanguagelearning}"
RELEASE_TYPE="${RELEASE_TYPE:-beta}"
GAME_VERSIONS="${GAME_VERSIONS:-26.3}"
LOADERS="${LOADERS:-neoforge}"

JAR="build/libs/malanguagelearning-${VERSION}.jar"

if [[ -z "${MODRINTH_TOKEN:-}" ]]; then
  echo "ERROR: MODRINTH_TOKEN is not set." >&2
  echo "Create a token at https://modrinth.com/settings/pats and export it." >&2
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: $JAR not found. Run ./gradlew build first." >&2
  exit 1
fi

CHANGELOG_FILE="${CHANGELOG:-}"
if [[ -z "$CHANGELOG_FILE" && -f CHANGELOG.md ]]; then
  CHANGELOG_FILE="CHANGELOG.md"
fi

# Build the JSON body with python so we do not fight quoting in bash.
BODY=$(python3 - "$PROJECT_ID" "$VERSION" "$CHANGELOG_FILE" "$RELEASE_TYPE" "$GAME_VERSIONS" "$LOADERS" <<'PY'
import json, sys, pathlib
project, version, changelog_file, release_type, game_versions, loaders = sys.argv[1:7]
changelog = ""
if changelog_file and pathlib.Path(changelog_file).is_file():
    changelog = pathlib.Path(changelog_file).read_text(encoding="utf-8")
body = {
    "name": f"MaLanguageLearning {version}",
    "version_number": version,
    "changelog": changelog,
    "dependencies": [],
    "game_versions": [v.strip() for v in game_versions.split(",") if v.strip()],
    "loaders": [l.strip() for l in loaders.split(",") if l.strip()],
    "release_channel": release_type,
    "featured": True,
    "project_id": project,
    "file_parts": ["file"],
    "primary_file": "file",
}
print(json.dumps(body))
PY
)

echo "Publishing $JAR to Modrinth project '$PROJECT_ID' as $RELEASE_TYPE ..."

curl -sS -X POST "https://api.modrinth.com/v2/version" \
  -H "Authorization: Bearer ${MODRINTH_TOKEN}" \
  -H "User-Agent: MaLanguageLearning-Publisher/1.0" \
  -F "data=${BODY}" \
  -F "file=@${JAR};type=application/java-archive" \
  -o /tmp/modrinth-response.json \
  -w "HTTP %{http_code}\n"

cat /tmp/modrinth-response.json
echo
