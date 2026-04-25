#!/usr/bin/env sh
# Activate the repo-managed git hooks under .githooks/.
#
# Run once after cloning. Idempotent: re-running it is a no-op.
# See CONTRIBUTING.md and ADR-0004 §7.

set -e
set -u

# Resolve the repo root from this script's location, so the script works
# regardless of the caller's current directory.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT"

git config core.hooksPath .githooks

printf '%s\n' "git hooks enabled: core.hooksPath = .githooks"
printf '%s\n' "the pre-commit hook will run gitleaks on staged changes."
printf '%s\n' "install gitleaks (brew install gitleaks) if you have not already."
