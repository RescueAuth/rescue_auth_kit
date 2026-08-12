#!/usr/bin/env bash
#
# ci-heartbeat.sh
#
# A tiny CI orchestration helper that runs a (potentially long, silent) command
# and periodically emits a lightweight heartbeat line so the CNB platform's
# "10 minutes with no output" watchdog does not kill a healthy but quiet stage.
#
# This is CI orchestration ONLY:
#   * It does NOT change test timeouts.
#   * It does NOT skip / ignore / split / retry any test.
#   * It does NOT add noise with `--info` / `--debug`.
#   * It DOES return the wrapped command's REAL exit code, so a failing
#     Gradle stage still FAILS the pipeline (never masked as success).
#
# Usage:
#   bash scripts/ci-heartbeat.sh <label> [--interval <seconds>] -- <command...>
#
# Examples:
#   bash scripts/ci-heartbeat.sh "app Robolectric tests" -- ./gradlew --no-daemon :app:testDebugUnitTest
#   bash scripts/ci-heartbeat.sh "core JVM tests" --interval 45 -- ./gradlew --no-daemon :core:test
#
# Design notes (see issue requirements):
#   * The heartbeat runs in its OWN process group (via `setsid`) so that we can
#     reliably `kill -- -<pid>` the entire process tree on exit. This avoids
#     leaving orphan background processes behind.
#   * `sleep` is used with sub-second granularity (sleep 0.5) in a tight loop,
#     so the wrapper notices command completion promptly while still emitting
#     at the requested human-scale interval.

set -euo pipefail

label="${1:-}"
if [[ -z "$label" ]]; then
  echo "ERROR: missing heartbeat label" >&2
  exit 2
fi
shift

interval=45
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --interval)
      interval="$2"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    *)
      echo "ERROR: unexpected argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "$#" -eq 0 ]]; then
  echo "ERROR: no command given to run" >&2
  exit 2
fi

[[ "$interval" =~ ^[0-9]+$ && "$interval" -gt 0 ]] || {
  echo "ERROR: invalid interval: '$interval' (must be a positive integer)" >&2
  exit 2
}

# Start the heartbeat in its own process group so it can be cleanly torn down.
setsid bash -c '
  while true; do
    sleep 0.5
    printf "[heartbeat] %s still running...\n" "$1"
    sleep "$2"
  done
' _ "$label" "$interval" &
heartbeat_pid=$!

# Run the real command in the FOREGROUND so we capture its real exit code.
set +e
"$@"
cmd_status=$?
set -e

# Tear down the heartbeat process group. kill -- -pid targets the whole group
# (including any sleeping subshell) so no orphan process is left behind.
kill -- -"$heartbeat_pid" 2>/dev/null || kill "$heartbeat_pid" 2>/dev/null || true
wait "$heartbeat_pid" 2>/dev/null || true

echo "[ci-heartbeat] wrapped command finished with exit code ${cmd_status}"
exit "$cmd_status"
