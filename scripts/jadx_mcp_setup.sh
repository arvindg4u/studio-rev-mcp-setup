#!/usr/bin/env bash
# =============================================================================
# jadx-mcp-server — persistent setup for this studio (pool-machine safe)
# -----------------------------------------------------------------------------
# Everything lands under /teamspace/studios/this_studio. apt is only used to
# download+extract debs into tools/apt (never system-wide). Fully idempotent.
# =============================================================================
set -uo pipefail
STUDIO="/teamspace/studios/this_studio"
MCP="${STUDIO}/jadx-mcp-server"
APT="${STUDIO}/tools/apt"
BIN="${MCP}/bin"
JDK="${STUDIO}/tools/jdk-21.0.12+8"
JADX_CLI="${STUDIO}/.revula/tools/jadx/bin/jadx"
PLUGIN_JAR="${MCP}/jadx-ai-mcp-6.4.0.jar"
CFG="${STUDIO}/.codex/config.toml"
GI="${STUDIO}/.gitignore"

info(){ echo "[INFO] $*"; }
ok(){   echo "[ OK ] $*"; }
warn(){ echo "[WARN] $*"; }
mkdir -p "${BIN}" "${APT}" "${MCP}/deps" "${MCP}/logs"
export JAVA_HOME="${JDK}"
export PATH="${JDK}/bin:${PATH}"

# 1. apt-prefix tools: Xvfb + xkbcomp + xkeyboard-config (persistent deb-extract)
if [[ ! -x "${APT}/usr/bin/Xvfb" || ! -x "${APT}/usr/bin/xkbcomp" ]]; then
  info "deb-extracting xvfb/xkbcomp/xkeyboard-config into tools/apt"
  bash "${MCP}/setup_deb_extract.sh" xvfb xauth xkbcomp xkeyboard-config
fi
if [[ -x "${APT}/usr/bin/Xvfb" ]]; then ok "Xvfb (${APT}/usr/bin/Xvfb)"; else warn "Xvfb missing"; fi
if [[ -x "${APT}/usr/bin/xkbcomp" ]]; then ok "xkbcomp (${APT}/usr/bin/xkbcomp)"; else warn "xkbcomp missing"; fi

# 2. xkbredirect.so shim (compile once; redirects /usr/bin/xkbcomp -> tools/apt)
if [[ ! -f "${APT}/lib/xkbredirect.so" ]]; then
  if gcc -O2 -shared -fPIC -o "${APT}/lib/xkbredirect.so" "${APT}/xkbredirect.c" -ldl; then
    ok "compiled xkbredirect.so"
  else
    warn "gcc compile of xkbredirect.so failed"
  fi
else
  ok "xkbredirect.so present"
fi

# 3. JADX-AI-MCP plugin installed into the persistent jadx config (~/.config/jadx
#    under studio via -Duser.home override)
if ! JAVA_OPTS="-Duser.home=${STUDIO}" "${JADX_CLI}" plugins --list 2>/dev/null | grep -q jadx-ai-mcp; then
  info "installing jadx-ai-mcp plugin (persistent config)"
  JAVA_OPTS="-Duser.home=${STUDIO}" "${JADX_CLI}" plugins --install-jar "${PLUGIN_JAR}" >/dev/null 2>&1 \
    && ok "jadx-ai-mcp plugin installed" || warn "plugin install failed"
else
  ok "jadx-ai-mcp plugin present"
fi

# 4. Python deps into MCP/deps (idempotent)
if [[ ! -d "${MCP}/deps/fastmcp" ]]; then
  info "installing python deps into ${MCP}/deps"
  /commands/python3 -m pip install --quiet --target "${MCP}/deps" -r "${MCP}/requirements.txt" 2>/dev/null \
    && ok "python deps installed" || warn "pip install failed (will retry next run)"
else
  ok "python deps present"
fi

# 5. launcher perms
chmod +x "${BIN}"/* 2>/dev/null || true
ok "launchers: ${BIN}/xvfb-mcp ${BIN}/jadx-gui-mcp ${BIN}/jadx-mcp-server ${BIN}/jadx-mcp-up"

# 6. register [mcp_servers.jadx_mcp] in .codex/config.toml (idempotent)
if ! grep -q 'mcp_servers.jadx_mcp' "${CFG}" 2>/dev/null; then
  cat >> "${CFG}" <<CFGEOF

[mcp_servers.jadx_mcp]
command = "/commands/python3"
args = ["/teamspace/studios/this_studio/jadx-mcp-server/jadx_mcp_server.py"]
cwd = "/teamspace/studios/this_studio/jadx-mcp-server"
startup_timeout_sec = 120.0

[mcp_servers.jadx_mcp.env]
HOME = "/teamspace/studios/this_studio"
JAVA_HOME = "/teamspace/studios/this_studio/tools/jdk-21.0.12+8"
PYTHONPATH = "/teamspace/studios/this_studio/jadx-mcp-server/deps"
PATH = "/teamspace/studios/this_studio/tools/jdk-21.0.12+8/bin:/home/zeus/miniconda3/envs/cloudspace/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
CFGEOF
  ok "registered [mcp_servers.jadx_mcp] in ${CFG}"
else
  ok "[mcp_servers.jadx_mcp] already registered"
fi

# 7. gitignore entries (idempotent)
if ! grep -q '^jadx-mcp-server/$' "${GI}" 2>/dev/null; then
  printf '\n# jadx-mcp-server (embedded repo, managed separately)\njadx-mcp-server/\n.java/\n' >> "${GI}"
  ok "gitignore updated"
else
  ok "gitignore already set"
fi

echo
echo "======================= JADX-MCP SUMMARY ======================="
echo "Setup:      bash scripts/jadx_mcp_setup.sh"
echo "Bring up:   ${BIN}/jadx-mcp-up"
echo "Server:     [mcp_servers.jadx_mcp] in ${CFG}"
echo "Verify:     curl -s http://127.0.0.1:8650/all-classes"
echo "================================================================"
