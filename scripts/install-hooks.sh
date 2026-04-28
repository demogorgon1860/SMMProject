#!/usr/bin/env bash
#
# Wires the .husky/ hook directory into this clone's git config. Run once after cloning.
#
# Usage:
#   ./scripts/install-hooks.sh
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"

git -C "$REPO_ROOT" config core.hooksPath .husky
chmod +x "$REPO_ROOT"/.husky/pre-commit "$REPO_ROOT"/.husky/pre-push 2>/dev/null || true

echo "✓ git core.hooksPath set to .husky"
echo "  → pre-commit and pre-push will run on the next commit/push"
echo
echo "Bypass with --no-verify when needed (e.g. WIP commits)."
