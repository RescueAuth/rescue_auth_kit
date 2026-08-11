#!/usr/bin/env bash
#
# validate-release-tag.sh
#
# FAIL-CLOSED gate for the production release pipeline. Verifies that the
# supplied value is a valid namespaced Current RescueAuth release tag:
#
#     ^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$
#
# and prints the derived release version (X.Y.Z) on success. Any missing or
# non-conforming value (legacy tags `legacy-vX.Y.Z`, `main`, feature branches,
# arbitrary strings) exits non-zero WITHOUT printing a version.
#
# This gate runs BEFORE the secret-bearing production signing stage, so an
# invalid tag never reaches the stage that imports the production Secret Repo.
#
# Usage: bash scripts/validate-release-tag.sh <tag>
#   prints: <version>            on success
#   exits 0 / 1 accordingly.
#
# Example:
#   bash scripts/validate-release-tag.sh rescueauth-v1.0.0   # -> 1.0.0, exit 0
#   bash scripts/validate-release-tag.sh legacy-v1.0.0       # -> exit 1 (legacy)
#   bash scripts/validate-release-tag.sh main                # -> exit 1

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_root/scripts/release-version.sh"

tag="${1:-}"
if [[ -z "$tag" ]]; then
  echo "ERROR: no release tag provided (must be a rescueauth-vX.Y.Z tag)." >&2
  exit 1
fi

if ! release_tag_is_valid "$tag"; then
  echo "ERROR: invalid production release tag '$tag': must match ^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$ (legacy tags and non-release branches are not accepted)." >&2
  exit 1
fi

version="$(release_version_from_tag "$tag")"
[[ -n "$version" ]] || { echo "ERROR: could not derive version from tag '$tag'." >&2; exit 1; }

printf '%s\n' "$version"
