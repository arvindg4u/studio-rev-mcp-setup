#!/usr/bin/env bash
# =============================================================================
# Full RE-MCP studio restore after a studio restart (pool-machine safe).
# Rebuilds everything under /teamspace/studios/this_studio and re-registers
# the Codex MCP servers. Fully idempotent.
#   SKIP_PIP=1 SKIP_ANDROID=1 bash scripts/studio_restore.sh   # fastest
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")" || exit 1
STUDIO="/teamspace/studios/this_studio"

echo "== [1/4] revula restore (repo, deps, tools, codex registration) =="
bash revula_restore.sh
echo
echo "== [2/4] jadx-mcp setup (Xvfb, plugin, deps, codex registration) =="
bash jadx_mcp_setup.sh
echo
echo "== [3/4] android-rev-mcp setup (deps, optional tools, codex registration) =="
bash android_rev_mcp_setup.sh
echo
echo "== [4/4] ghidra-mcp setup (read-write registration) =="
bash ghidra_mcp_setup.sh
echo
echo "Restore complete. Bring up the jadx stack:"
echo "  ${STUDIO}/jadx-mcp-server/bin/jadx-mcp-up"
echo "Then start a FRESH codex session (not resume) so all MCP servers attach."
