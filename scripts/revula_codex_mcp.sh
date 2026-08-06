#!/usr/bin/env bash
# =============================================================================
# Register the revula MCP server in ~/.codex/config.toml (persistent).
# Replaces any existing [mcp_servers.revula] block. Idempotent.
# NOTE: no GHIDRA_HEADLESS / GHIDRA_INSTALL_DIR — revula Ghidra is disabled;
# ghidra-headless-mcp already covers Ghidra (tools/ghidra_12.1.2_PUBLIC).
# =============================================================================
set -uo pipefail

STUDIO="/teamspace/studios/this_studio"
CODEX_CFG="${STUDIO}/.codex/config.toml"
TMP_CFG="$(mktemp /tmp/revula_codex.XXXXXX.toml)"

# Drop any existing [mcp_servers.revula] / [mcp_servers.revula.*] block.
awk '
/^\[mcp_servers\.revula(\]|\.)/ { inblock=1; next }
inblock && /^\[/ { inblock=0 }
!inblock { print }
' "${CODEX_CFG}" > "${TMP_CFG}"

cat >> "${TMP_CFG}" <<'BLOCK'

[mcp_servers.revula]
command = "/commands/python3"
args = ["-m", "revula.server"]
cwd = "/teamspace/studios/this_studio/revula"
startup_timeout_sec = 120

[mcp_servers.revula.env]
HOME = "/teamspace/studios/this_studio"
JAVA_HOME = "/teamspace/studios/this_studio/tools/jdk-21.0.12+8"
PATH = "/teamspace/studios/this_studio/.revula/bin:/teamspace/studios/this_studio/tools/jdk-21.0.12+8/bin:/home/zeus/miniconda3/envs/cloudspace/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
PYTHONPATH = "/teamspace/studios/this_studio/revula/deps"
BLOCK

# Validate TOML before replacing the real config.
/commands/python3 - "${TMP_CFG}" <<'VALIDATE_EOF'
import sys
import tomllib
with open(sys.argv[1], "rb") as f:
    tomllib.load(f)
VALIDATE_EOF
if [[ $? -eq 0 ]]; then
  cp "${TMP_CFG}" "${CODEX_CFG}"
  rm -f "${TMP_CFG}"
  echo "[ OK ] revula MCP block written to ${CODEX_CFG}"
else
  rm -f "${TMP_CFG}"
  echo "[FAIL] generated config failed TOML validation; nothing changed" >&2
  exit 1
fi
