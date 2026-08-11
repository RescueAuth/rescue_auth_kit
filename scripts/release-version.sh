#!/usr/bin/env bash
#
# release-version.sh
#
# Reusable release-tag parsing and version-extraction helpers for the
# RescueAuth Build & Release Workflow.
#
# This file is INTENDED TO BE SOURCED (`source`) by other scripts and by the
# offline regression test suite. It deliberately has NO top-level side effects
# and never calls main(), so sourcing it is always safe.
#
# Release policy (see README "Branch / Version / Release Policy"):
#
#   * New RescueAuth stable releases use the namespaced tag namespace:
#         rescueauth-vX.Y.Z
#     where X.Y.Z is the Android `versionName` (e.g. tag `rescueauth-v1.0.0`
#     corresponds to Android `versionName = 1.0.0`).
#   * The `rescueauth-` prefix is a Git TAG namespace, NOT part of the Android
#     versionName.
#   * Legacy unprefixed tags (`v1.0.0`, `v1.1.0`, `v1.2.0`, ...) belong to the
#     legacy RescueAuth app (applicationId `com.xincy.rescue_auth_kit`) and MUST
#     NOT be accepted by the current production release path.
#   * `main` and feature/fix branches MUST NOT be production-released.
#
# The canonical release-tag regex (POSIX ERE, applied to the whole tag string):
#     ^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$
#
# Usage from a sourced script:
#     source scripts/release-version.sh
#     if release_tag_is_valid "$tag"; then
#         ver=$(release_version_from_tag "$tag")
#         ...
#     fi

# Fail-fast when sourced directly by a shell that runs the file as a script.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    echo "ERROR: release-version.sh is a helper library and must be sourced, not executed." >&2
    exit 2
fi

# Canonical POSIX ERE for a namespaced RescueAuth release tag.
readonly RELEASE_TAG_REGEX='^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$'

# release_tag_is_valid <tag>
#   Returns 0 (true) when <tag> matches the namespaced release-tag regex,
#   otherwise returns 1 (false).
release_tag_is_valid() {
    local tag="${1:-}"
    [[ -n "$tag" && "$tag" =~ $RELEASE_TAG_REGEX ]]
}

# release_version_from_tag <tag>
#   Given a VALID namespaced release tag `rescueauth-vX.Y.Z`, prints `X.Y.Z`.
#   The caller MUST have already validated the tag with release_tag_is_valid.
#   Prints nothing and returns 1 when the tag is not valid (fail closed).
release_version_from_tag() {
    local tag="${1:-}"
    if ! release_tag_is_valid "$tag"; then
        return 1
    fi
    # Strip the literal `rescueauth-v` prefix. The regex guarantees the
    # remainder is `X.Y.Z` with three dot-separated numeric components.
    printf '%s\n' "${tag#rescueauth-v}"
}
