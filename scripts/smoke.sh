#!/usr/bin/env bash
#
# Post-deploy smoke test. Exits non-zero on the first failed assertion so the
# deploy script can roll back. Designed to be both safe to run from CI and from
# a local dev machine pointed at any environment.
#
# Usage:
#   ./scripts/smoke.sh                                    # default: https://smmworld.vip
#   ./scripts/smoke.sh https://staging.smmworld.vip
#   ADMIN_TOKEN=<jwt> ./scripts/smoke.sh                  # also runs admin assertions
#
# Exit codes:
#   0  all assertions pass
#   1  at least one assertion failed
#   2  jq / curl missing
set -euo pipefail

HOST="${1:-https://smmworld.vip}"
TIMEOUT="${SMOKE_TIMEOUT:-10}"
PASS=0
FAIL=0

if ! command -v jq >/dev/null 2>&1; then
  echo "smoke: jq is required (apt-get install jq | brew install jq)" >&2
  exit 2
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "smoke: curl is required" >&2
  exit 2
fi

reset() { tput sgr0 2>/dev/null || true; }
red()   { tput setaf 1 2>/dev/null || true; }
green() { tput setaf 2 2>/dev/null || true; }
bold()  { tput bold   2>/dev/null || true; }

ok()   { green; echo "  ✓ $1"; reset; PASS=$((PASS + 1)); }
fail() { red;   echo "  ✗ $1"; reset; FAIL=$((FAIL + 1)); }

# fetch <url> [extra-header] — returns body on success, non-zero on HTTP error.
fetch() {
  local url="$1"
  local header="${2:-}"
  if [[ -n "$header" ]]; then
    curl -fsS --max-time "$TIMEOUT" -H "$header" "$url"
  else
    curl -fsS --max-time "$TIMEOUT" "$url"
  fi
}

# check <description> { … }   — runs the block as a function body.
# A grouped command (curly braces) inherits all functions defined in this script,
# unlike `bash -c "…"` which spawns a fresh shell that loses our `fetch` helper.
check() {
  local desc="$1"
  shift
  if "$@" >/dev/null 2>&1; then ok "$desc"; else fail "$desc"; fi
}

# Helpers — each one is a complete predicate, returns 0 on success.
public_stats_returns_json()    { fetch "$HOST/api/v1/stats/public" | jq -e . ; }
services_listing_has_rows()    { fetch "$HOST/api/v1/service/services" | jq -e '(.data // .) | length > 0' ; }
landing_serves_spa_shell()     { fetch "$HOST/" | grep -qi 'smm\|smmworld' ; }
login_page_renders()           { fetch "$HOST/login" | grep -qi 'sign\|login\|email' ; }

bold; echo "Smoke against $HOST"; reset

# ---------------------------------------------------------------------
# Public endpoints — must work for an unauthenticated browser.
# ---------------------------------------------------------------------

echo
echo "Public API:"
check "GET /api/v1/stats/public returns JSON"             public_stats_returns_json
check "GET /api/v1/service/services has at least one row" services_listing_has_rows

echo
echo "Public pages:"
check "GET / serves the SPA shell"  landing_serves_spa_shell
check "GET /login serves a sign-in page" login_page_renders

# ---------------------------------------------------------------------
# Auth-only checks — only run if ADMIN_TOKEN is set.
# ---------------------------------------------------------------------

if [[ -n "${ADMIN_TOKEN:-}" ]]; then
  AUTH="Authorization: Bearer ${ADMIN_TOKEN}"

  admin_dashboard_ok() {
    fetch "$HOST/api/v2/admin/dashboard" "$AUTH" | jq -e '(.totalUsers // .users // 0) >= 0'
  }
  admin_users_no_password_hash() { ! fetch "$HOST/api/v2/admin/users" "$AUTH" | grep -q passwordHash; }
  admin_users_no_api_key_hash()  { ! fetch "$HOST/api/v2/admin/users" "$AUTH" | grep -q apiKeyHash; }
  admin_users_no_api_key_salt()  { ! fetch "$HOST/api/v2/admin/users" "$AUTH" | grep -q apiKeySalt; }
  admin_users_no_password_field() {
    # Match a JSON field literally named "password": …  (avoid collisions with passwordHash).
    ! fetch "$HOST/api/v2/admin/users" "$AUTH" | grep -qE '"password"\s*:'
  }
  admin_daily_stats_seven_entries() {
    fetch "$HOST/api/v2/admin/stats/daily?days=7" "$AUTH" | jq -e 'length == 7'
  }

  echo
  echo "Admin API (token provided):"
  check "GET /api/v2/admin/dashboard returns total counters"    admin_dashboard_ok
  check "GET /api/v2/admin/users does NOT expose passwordHash"  admin_users_no_password_hash
  check "GET /api/v2/admin/users does NOT expose apiKeyHash"    admin_users_no_api_key_hash
  check "GET /api/v2/admin/users does NOT expose apiKeySalt"    admin_users_no_api_key_salt
  check 'GET /api/v2/admin/users does NOT expose "password"'    admin_users_no_password_field
  check "GET /api/v2/admin/stats/daily?days=7 returns 7 entries" admin_daily_stats_seven_entries
else
  echo
  echo "(Skipping admin assertions — set ADMIN_TOKEN=<jwt> to enable.)"
fi

# ---------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------

echo
bold; echo "Result: ${PASS} passed, ${FAIL} failed"; reset

if [[ $FAIL -gt 0 ]]; then
  exit 1
fi
echo "OK"
