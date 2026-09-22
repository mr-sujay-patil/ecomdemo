#!/usr/bin/env bash
#
# Recreates the EcomDemo quality gate on a SonarQube server, and prints the analysis command.
#
# WHY THIS EXISTS. A quality gate is configured in SonarQube's own database, not in this
# repository - so `docker compose -f compose.sonar.yaml down -v` deletes it, and a colleague
# starting the stack gets Sonar's defaults instead of ours. That makes the gate the one piece of
# this project's quality setup that is not in Git, which is exactly the kind of configuration
# that quietly drifts. This script is the fix: the gate as code, applied through the Web API.
#
#   Usage:  docker compose -f compose.sonar.yaml up -d
#           scripts/sonar-setup.sh                      # uses admin/admin on first run
#           SONAR_ADMIN_PASSWORD=... scripts/sonar-setup.sh
#
# It is idempotent: run it as often as you like. An existing gate or condition is left alone.

set -uo pipefail

SONAR_URL="${SONAR_URL:-http://localhost:9000}"
SONAR_ADMIN_USER="${SONAR_ADMIN_USER:-admin}"
SONAR_ADMIN_PASSWORD="${SONAR_ADMIN_PASSWORD:-admin}"
PROJECT_KEY="${SONAR_PROJECT_KEY:-ecomdemo}"
PROJECT_NAME="${SONAR_PROJECT_NAME:-EcomDemo}"
GATE_NAME="${SONAR_GATE_NAME:-EcomDemo way}"

pass() { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
skip() { printf '  \033[33m--\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n        %s\n' "$1" "$2"; }

# api <METHOD> <path> [key=value ...] -> echoes the HTTP status; body lands in $API_BODY
#
# The status matters at least as much as the body. SonarQube answers an authentication failure
# with 401 and an EMPTY body, so a script that only inspects the body reports a blank error for
# the most common setup mistake there is - which is exactly what the first version of this
# script did, six times in a row.
#
# The body goes to a FILE rather than being echoed alongside the status, because a caller writing
# `status="$(api ...)"` runs the function in a subshell: anything the function assigns to a
# global there is discarded when the subshell exits, and the status silently reads as empty.
# This is the same shape as `request()` in smoke-test.sh, for the same reason.
API_BODY="$(mktemp)"
trap 'rm -f "$API_BODY"' EXIT

api() {
    local method="$1" path="$2"; shift 2
    local args=() d
    for d in "$@"; do args+=(--data-urlencode "$d"); done
    if [ ${#args[@]} -eq 0 ]; then
        curl -sS -o "$API_BODY" -w '%{http_code}' \
            -u "$SONAR_ADMIN_USER:$SONAR_ADMIN_PASSWORD" -X "$method" "$SONAR_URL$path" 2>/dev/null
    elif [ "$method" = "GET" ]; then
        # -G moves the --data-urlencode values into the query string, ENCODED. Building the URL
        # by hand instead would break on the first value containing a space - and the gate is
        # called "EcomDemo way".
        curl -sS -o "$API_BODY" -w '%{http_code}' -G \
            -u "$SONAR_ADMIN_USER:$SONAR_ADMIN_PASSWORD" "$SONAR_URL$path" \
            "${args[@]}" 2>/dev/null
    else
        curl -sS -o "$API_BODY" -w '%{http_code}' \
            -u "$SONAR_ADMIN_USER:$SONAR_ADMIN_PASSWORD" -X "$method" "$SONAR_URL$path" \
            "${args[@]}" 2>/dev/null
    fi
}

body() { cat "$API_BODY"; }

# Turns an empty body into something a reader can act on.
explain() {
    local status="$1" text
    text="$(body)"
    case "$status" in
        401) echo "401 Unauthorized - wrong admin password. Pass it as SONAR_ADMIN_PASSWORD=..." ;;
        403) echo "403 Forbidden - '$SONAR_ADMIN_USER' lacks the Administer Quality Gates permission" ;;
        000|"") echo "no response - is SonarQube running at $SONAR_URL?" ;;
        *)   echo "HTTP $status ${text:-(empty body)}" ;;
    esac
}

printf '\033[1mSonarQube setup\033[0m  (%s)\n\n' "$SONAR_URL"

# --- 0. The server has to be UP, not merely listening --------------------------------------
# SonarQube answers on port 9000 for a minute or more while Elasticsearch is still starting, and
# every call below would fail with a confusing error during that window.
printf 'Waiting for SonarQube to report UP'
for _ in $(seq 1 60); do
    if curl -sf "$SONAR_URL/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; then
        printf '\n'; pass "server is UP"; break
    fi
    printf '.'; sleep 5
done
if ! curl -sf "$SONAR_URL/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; then
    printf '\n'; fail "server is UP" "still not ready at $SONAR_URL"
    printf '\nStart it with: docker compose -f compose.sonar.yaml up -d\n'
    exit 1
fi

# --- 0b. The credentials have to work -------------------------------------------------------
# Checked once, up front. Without this every step below fails with the same blank 401 and the
# real problem - a password that was changed on first login - is buried six failures deep.
# Note this endpoint answers 200 with {"valid":false} for a wrong password rather than 401, so
# the body has to be checked as well as the status.
STATUS="$(api GET /api/authentication/validate)"
if [ "$STATUS" != "200" ] || ! body | grep -q '"valid":true'; then
    if [ "$STATUS" = "200" ]; then
        fail "credentials for '$SONAR_ADMIN_USER'" "the server rejected the password"
    else
        fail "credentials for '$SONAR_ADMIN_USER'" "$(explain "$STATUS")"
    fi
    printf '\nSonarQube forces a password change on first login, so admin/admin stops working\n'
    printf 'as soon as anyone has logged in. Re-run with:\n\n'
    printf "  SONAR_ADMIN_PASSWORD='<the password>' scripts/sonar-setup.sh\n\n"
    exit 1
fi
pass "authenticated as '$SONAR_ADMIN_USER'"

# --- 1. The project -------------------------------------------------------------------------
# Analysis auto-creates it, but creating it here means the gate can be attached before the first
# run rather than after it.
api GET /api/projects/search "projects=$PROJECT_KEY" > /dev/null
if body | grep -q "\"key\":\"$PROJECT_KEY\""; then
    skip "project '$PROJECT_KEY' already exists"
else
    STATUS="$(api POST /api/projects/create "project=$PROJECT_KEY" "name=$PROJECT_NAME")"
    if [ "$STATUS" = "200" ]; then
        pass "created project '$PROJECT_KEY'"
    else
        fail "create project '$PROJECT_KEY'" "$(explain "$STATUS")"
        exit 1
    fi
fi

# --- 2. The quality gate --------------------------------------------------------------------
api GET "/api/qualitygates/list" > /dev/null
if body | grep -q "\"name\":\"$GATE_NAME\""; then
    skip "quality gate '$GATE_NAME' already exists"
else
    STATUS="$(api POST /api/qualitygates/create "name=$GATE_NAME")"
    if [ "$STATUS" = "200" ]; then
        pass "created quality gate '$GATE_NAME'"
    else
        fail "create quality gate '$GATE_NAME'" "$(explain "$STATUS")"
        exit 1
    fi
fi

# --- 3. The conditions ----------------------------------------------------------------------
# All of them are on NEW CODE, and that is the whole idea behind a gate. A rule about the WHOLE
# project either passes on day one and never teaches anything, or fails on day one and gets
# switched off. "Leave it cleaner than you found it" is a rule a team can actually keep, and it
# converges on the same place without ever blocking unrelated work.
#
# A new gate is created carrying Sonar's own defaults, so most of these are already present; the
# script states every one explicitly anyway, so this file - not the server - is the record of
# what the gate means.
# The gate's current conditions, fetched once. Asked for up front rather than inferred from the
# error of a failed create: SonarQube's "already exists" message is built with MessageFormat and
# blows up on metrics whose display name contains a percent sign - `new_duplicated_lines_density`
# is "Duplicated Lines (%) on New Code", and the API answers `{"errors":[{"msg":"Conversion =
# ')'"}]}` instead. Checking first is both more robust and more obvious.
api GET /api/qualitygates/show "name=$GATE_NAME" > /dev/null
EXISTING_CONDITIONS="$(body)"

add_condition() { # add_condition <metric> <op> <threshold> <why>
    local metric="$1" op="$2" threshold="$3" why="$4" status
    if printf '%s' "$EXISTING_CONDITIONS" | grep -q "\"metric\":\"$metric\""; then
        skip "$metric — already set ($why)"
        return
    fi
    status="$(api POST /api/qualitygates/create_condition \
        "gateName=$GATE_NAME" "metric=$metric" "op=$op" "error=$threshold")"
    if [ "$status" = "200" ]; then
        pass "$metric $op $threshold — $why"
    else
        fail "$metric $op $threshold" "$(explain "$status")"
    fi
}

printf '\nConditions (all on NEW code):\n'
add_condition new_coverage LT 80 \
    "new code must be covered; the project sits at ~97% line coverage, so lowering this to the phase's suggested 70 would be loosening a gate we already beat"
add_condition new_violations GT 0 \
    "no new issue of any severity — the cheapest moment to fix one is before it is merged"
add_condition new_duplicated_lines_density GT 3 \
    "copy-paste is the cheapest thing to catch and the most expensive to live with"
add_condition new_security_hotspots_reviewed LT 100 \
    "a hotspot is a question, not a defect: this demands an answer, not a clean result"
add_condition new_reliability_rating GT 1 \
    "no new bug may be introduced"
add_condition new_security_rating GT 1 \
    "no new vulnerability may be introduced"

# --- 4. Attach it ---------------------------------------------------------------------------
printf '\n'
STATUS="$(api POST /api/qualitygates/select "gateName=$GATE_NAME" "projectKey=$PROJECT_KEY")"
case "$STATUS" in
    200|204) pass "attached '$GATE_NAME' to '$PROJECT_KEY'" ;;
    *)       fail "attach '$GATE_NAME' to '$PROJECT_KEY'" "$(explain "$STATUS")" ;;
esac

# --- What to run next -------------------------------------------------------------------------
cat <<NEXT

Next, generate a token (My Account -> Security) and analyse:

  ./mvnw clean verify sonar:sonar \\
    -Dsonar.host.url=$SONAR_URL \\
    -Dsonar.token=<token> \\
    -Dsonar.projectKey=$PROJECT_KEY \\
    -Dsonar.qualitygate.wait=true

\`verify\` before \`sonar:sonar\` is not optional: the scanner reads the compiled classes and the
merged JaCoCo report, and analysing without them reports no coverage at all.

\`-Dsonar.qualitygate.wait=true\` makes the build FAIL when the gate fails, instead of printing a
link to a red dashboard nobody opens.

Results: $SONAR_URL/dashboard?id=$PROJECT_KEY
NEXT
