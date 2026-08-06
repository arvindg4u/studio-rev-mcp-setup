#!/usr/bin/env bash
# =============================================================================
# ghidra-headless-mcp — persistent read-write registration for this studio.
# Ensures [mcp_servers.ghidra_headless_mcp.env] sets GHIDRA_HEADLESS_MCP_READ_ONLY=0
# so sessions open read-write (write tools work) after every restore.
# Idempotent; safe to re-run.
# =============================================================================
set -uo pipefail
STUDIO="/teamspace/studios/this_studio"
CFG="${STUDIO}/.codex/config.toml"

info(){ echo "[INFO] $*"; }
ok(){   echo "[ OK ] $*"; }

if ! grep -q 'mcp_servers.ghidra_headless_mcp' "${CFG}" 2>/dev/null; then
  cat >> "${CFG}" <<CFGEOF

[mcp_servers.ghidra_headless_mcp]
command = "/commands/python3"
args = ["/teamspace/studios/this_studio/ghidra-headless-mcp/ghidra_headless_mcp.py", "--ghidra-install-dir", "/teamspace/studios/this_studio/tools/ghidra_12.1.2_PUBLIC"]
cwd = "/teamspace/studios/this_studio/ghidra-headless-mcp"
startup_timeout_sec = 180.0

[mcp_servers.ghidra_headless_mcp.env]
GHIDRA_INSTALL_DIR = "/teamspace/studios/this_studio/tools/ghidra_12.1.2_PUBLIC"
GHIDRA_HEADLESS_MCP_READ_ONLY = "0"
HOME = "/teamspace/studios/this_studio"
JAVA_HOME = "/teamspace/studios/this_studio/tools/jdk-21.0.12+8"
PYTHONPATH = "/teamspace/studios/this_studio/ghidra-headless-mcp/deps"
CFGEOF
  ok "registered [mcp_servers.ghidra_headless_mcp] (read-write)"
elif ! grep -q 'GHIDRA_HEADLESS_MCP_READ_ONLY' "${CFG}" 2>/dev/null; then
  python3 - <<'PYEOF'
import re
p = "/teamspace/studios/this_studio/.codex/config.toml"
s = open(p).read()
old = 'GHIDRA_INSTALL_DIR = "/teamspace/studios/this_studio/tools/ghidra_12.1.2_PUBLIC"'
new = old + '\nGHIDRA_HEADLESS_MCP_READ_ONLY = "0"'
assert old in s
s = s.replace(old, new, 1)
open(p, "w").write(s)
PYEOF
  ok "added GHIDRA_HEADLESS_MCP_READ_ONLY=0 to ghidra env"
else
  ok "ghidra read-write env already set"
fi
