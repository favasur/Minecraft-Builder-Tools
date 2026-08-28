#!/usr/bin/env bash
#
# Builds all module jars and (re)creates the GitHub release for a version,
# with release notes extracted from CHANGELOG.md.
#
# usage: scripts/release.sh <version>      e.g. scripts/release.sh 0.2.0
#
# What it does:
#   1. Runs `collectJars` in every module, collecting the jars into dist/.
#   2. Extracts the "## [<version>]" section from CHANGELOG.md as the release body
#      (plus an auto-generated Downloads table), so the release notes stay in
#      sync with the changelog by construction.
#   3. Creates the GitHub release (or edits it if it already exists, e.g. when
#      re-releasing the same version) and uploads all dist/ jars.
#
# Requires the GitHub CLI (`gh`) authenticated against the repo.
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    echo "usage: $0 <version>   e.g. $0 0.2.0" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHANGELOG="$ROOT/CHANGELOG.md"
TITLE="Builder Tools $VERSION"
HEADING="## [$VERSION]"

# --- 1. Build all five module jars into dist/ ---------------------------------
cd "$ROOT"
echo "== Building jars (root)"
./gradlew collectJars -q
for module in fabric-262 neoforge-262 forge-262 fabric-1211; do
    echo "== Building jars ($module)"
    (cd "$module" && ./gradlew collectJars -q)
done

# --- 2. Extract the changelog section for this version -------------------------
if [ ! -f "$CHANGELOG" ]; then
    echo "error: $CHANGELOG not found" >&2
    exit 1
fi
NOTES="$(awk -v heading="$HEADING" '
    /^## \[/ {
        if (found) exit
        if (index($0, heading) == 1) found = 1
    }
    found { print }
' "$CHANGELOG")"
if [ -z "$NOTES" ]; then
    echo "error: no \"$HEADING\" section in $CHANGELOG" >&2
    exit 1
fi

# --- 3. Downloads table from the freshly built jars ----------------------------
loader_name() {
    case "$1" in
        neoforge) echo "NeoForge" ;;
        forge)    echo "Forge" ;;
        fabric)   echo "Fabric" ;;
        *)        echo "$1" ;;
    esac
}

TABLE="## Downloads

| Loader | Minecraft | Jar |
|---|---|---|"
for jar in dist/buildertools-*.jar; do
    [ -e "$jar" ] || continue
    name="$(basename "$jar")"
    rest="${name#buildertools-}"              # <loader>-<mc>-<version>.jar
    loader="$(loader_name "${rest%%-*}")"
    rest="${rest#*-}"                         # <mc>-<version>.jar
    mc="${rest%%-*}"
    TABLE="$TABLE
| $loader | $mc | \`$name\` |"
done

BODY="$NOTES

$TABLE"

# --- 4. Create or edit the release, then upload the jars -----------------------
if gh release view "$VERSION" >/dev/null 2>&1; then
    echo "== Release $VERSION exists - updating notes"
    gh release edit "$VERSION" --title "$TITLE" --notes "$BODY"
else
    echo "== Creating release $VERSION"
    gh release create "$VERSION" --title "$TITLE" --notes "$BODY"
fi

echo "== Uploading jars"
gh release upload "$VERSION" dist/buildertools-*.jar --clobber

echo "== Done: https://github.com/${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}/releases/tag/$VERSION"
