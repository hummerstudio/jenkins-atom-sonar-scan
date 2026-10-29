#!/usr/bin/env bash
# sonar-scanner is faked: the point is the atom's contract (arguments, env token, report-task parsing, gate), not Sonar.
set -uo pipefail
cd "$(dirname "$0")/.."
export BUILD_NUMBER=5 JOB_NAME=parcel
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"
[ -f "$JAR" ] || fail "no jar at $JAR"

# a scanner that records its argv and writes what the real one writes
mkdir -p "$work/bin"
cat >"$work/bin/sonar-scanner" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$SMOKE_ARGV"
[ -n "${SONAR_TOKEN:-}" ] && printf 'token-seen\n' > "$SMOKE_TOKEN"
mkdir -p .scannerwork
cat > .scannerwork/report-task.txt <<EOF
projectKey=$SMOKE_PROJECT
serverUrl=http://sonar.local
dashboardUrl=http://sonar.local/dashboard?id=$SMOKE_PROJECT
ceTaskId=AX123
ceTaskUrl=http://sonar.local/api/ce/task?id=AX123
EOF
SH
chmod +x "$work/bin/sonar-scanner"

dir="$work/scan"; mkdir -p "$dir"
export SMOKE_ARGV="$work/argv.txt" SMOKE_TOKEN="$work/token.txt" SMOKE_PROJECT=parcel
printf '{"projectKey": "parcel", "hostUrl": "http://sonar.local", "sources": "src",\n "waitForQualityGate": "false", "extraArgs": "-Dsonar.java.binaries=target"}' >"$dir/input.json"
printf '{"sonarToken": "smoke-token"}' >"$dir/.secrets.json"

PATH="$work/bin:$PATH" run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"

[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" dashboardUrl)" = "http://sonar.local/dashboard?id=parcel" ] || fail "dashboard: $(cat "$dir/output.json")"
[ "$(output "$dir" taskId)" = "AX123" ] || fail "task id"
grep -q -- "-Dsonar.projectKey=parcel" "$work/argv.txt" || fail "project key not passed"
grep -q -- "-Dsonar.sources=src" "$work/argv.txt" || fail "sources not passed"
grep -q -- "-Dsonar.java.binaries=target" "$work/argv.txt" || fail "extra args not passed"
grep -q "smoke-token" "$work/argv.txt" && fail "the token must not be in argv"
[ -f "$work/token.txt" ] || fail "the token must reach the scanner through the environment"
grep -q "smoke-token" "$dir/log.txt" && fail "the token must not be in the log"

# a missing projectKey is a user error, not a crash
dir="$work/nokey"; mkdir -p "$dir"
printf '{"projectKey": "", "waitForQualityGate": "false"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >/dev/null 2>&1 && fail "an empty projectKey must fail"
grep -q "projectKey is required" "$dir/output.json" || fail "message not readable: $(cat "$dir/output.json")"

echo "smoke ok: argv, token via env not argv, report-task parsing, missing projectKey"
