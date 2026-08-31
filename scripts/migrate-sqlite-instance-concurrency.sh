#!/usr/bin/env bash
# Stop every Hub process and take a verified backup before running this script.
# Adds hub_instance.max_concurrency to an existing SQLite database (default 1, range 1..4).
# Safe to execute repeatedly (idempotent).
set -Eeuo pipefail

usage() {
    printf 'Usage: %s <path-to-opencli-hub.db>\n' "$0" >&2
    printf '   or: OPENCLI_HUB_SQLITE_PATH=/path/to/opencli-hub.db %s\n' "$0" >&2
    exit 1
}

db_file="${1:-${OPENCLI_HUB_SQLITE_PATH:-}}"

if [[ -z "${db_file}" ]]; then
    printf 'Error: SQLite database path not provided.\n' >&2
    usage
fi

if [[ ! -f "${db_file}" ]]; then
    printf 'Error: Database file does not exist: %s\n' "${db_file}" >&2
    exit 1
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
    printf 'Error: sqlite3 CLI is required but not found in PATH.\n' >&2
    exit 1
fi

# Verify table hub_instance exists
table_count=$(sqlite3 "${db_file}" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='hub_instance';")
if [[ "${table_count}" -eq 0 ]]; then
    printf 'Error: Table hub_instance not found in %s\n' "${db_file}" >&2
    exit 1
fi

# Check if max_concurrency column already exists in hub_instance
col_exists=$(sqlite3 "${db_file}" "PRAGMA table_info(hub_instance);" | awk -F'|' '$2 == "max_concurrency" { count++ } END { print count+0 }')

if [[ "${col_exists}" -gt 0 ]]; then
    printf 'hub_instance.max_concurrency already present in %s. Skipping migration.\n' "${db_file}"
    exit 0
fi

# Perform migration: add column max_concurrency with default 1
printf 'Adding column max_concurrency to hub_instance in %s...\n' "${db_file}"
sqlite3 "${db_file}" <<'EOF'
ALTER TABLE hub_instance ADD COLUMN max_concurrency int NOT NULL DEFAULT 1;
EOF

# Verify migration succeeded
col_exists_after=$(sqlite3 "${db_file}" "PRAGMA table_info(hub_instance);" | awk -F'|' '$2 == "max_concurrency" { count++ } END { print count+0 }')
if [[ "${col_exists_after}" -eq 1 ]]; then
    printf 'Successfully added max_concurrency column to hub_instance in %s.\n' "${db_file}"
    exit 0
else
    printf 'Error: Failed to verify max_concurrency column after migration in %s.\n' "${db_file}" >&2
    exit 1
fi
