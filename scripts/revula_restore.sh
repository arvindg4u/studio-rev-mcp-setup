#!/usr/bin/env bash
# =============================================================================
# One-shot revula restore after a studio restart (pool-machine safe).
# Rebuilds everything under /teamspace/studios/this_studio and re-registers
# the Codex MCP server. Fully idempotent.
#   SKIP_PIP=1 SKIP_ANDROID=1 bash scripts/revula_restore.sh   # fastest
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")" || exit 1

echo "== [1/3] revula_persistent_setup.sh (repo, python deps, core tools) =="
bash revula_persistent_setup.sh
echo
echo "== [2/3] revula_tools_extras.sh (binwalk, tshark, qemu, mono, ruby, android) =="
bash revula_tools_extras.sh
echo
echo "== [3/3] revula_codex_mcp.sh (register MCP server in .codex/config.toml) =="
bash revula_codex_mcp.sh
echo
echo "Restore complete. Restart codex sessions so the revula MCP server is picked up."
echo 'Verify: codex exec "call revula_admin_status"'
