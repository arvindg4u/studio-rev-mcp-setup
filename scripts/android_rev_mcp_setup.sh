#!/usr/bin/env bash
# =============================================================================
# android-reverse-engineering-mcp-server — persistent setup for this studio
# (pool-machine safe). Everything lands under /teamspace/studios/this_studio.
# Optional tools (vineflower, dex2jar) install into the persistent $HOME/.local
# prefix; no system-wide apt. Fully idempotent.
# =============================================================================
set -uo pipefail
STUDIO="/teamspace/studios/this_studio"
MCP="${STUDIO}/android-reverse-engineering-mcp-server"
CFG="${STUDIO}/.codex/config.toml"
GI="${STUDIO}/.gitignore"
JDK="${STUDIO}/tools/jdk-21.0.12+8"
LOCAL_BIN="${STUDIO}/.local/bin"
LOCAL_SHARE="${STUDIO}/.local/share"

info(){ echo "[INFO] $*"; }
ok(){   echo "[ OK ] $*"; }
warn(){ echo "[WARN] $*"; }
mkdir -p "${LOCAL_BIN}" "${LOCAL_SHARE}/vineflower" "${LOCAL_SHARE}/dex2jar" "${MCP}/deps"
export JAVA_HOME="${JDK}"
export PATH="${JDK}/bin:${LOCAL_BIN}:${PATH}"

# 1. Python deps into MCP/deps (idempotent; pinned to mcp 1.x in requirements.txt)
if [[ ! -d "${MCP}/deps/mcp/server/fastmcp" ]]; then
  info "installing python deps into ${MCP}/deps"
  /commands/python3 -m pip install --quiet --target "${MCP}/deps" -r "${MCP}/requirements.txt" 2>/dev/null \
    && ok "python deps installed" || warn "pip install failed (will retry next run)"
else
  ok "python deps present"
fi

# 2. vineflower (optional decompiler) -> persistent $HOME/.local
VINE_JAR="${LOCAL_SHARE}/vineflower/vineflower.jar"
if [[ ! -f "${VINE_JAR}" ]]; then
  info "downloading vineflower jar"
  curl -fsSL -o "${VINE_JAR}" "https://github.com/Vineflower/vineflower/releases/download/1.12.0/vineflower-1.12.0.jar" \
    && ok "vineflower jar installed" || warn "vineflower download failed"
else
  ok "vineflower jar present"
fi
if [[ ! -x "${LOCAL_BIN}/vineflower" ]]; then
  printf '#!/usr/bin/env bash\nexec java -jar "$HOME/.local/share/vineflower/vineflower.jar" "$@"\n' > "${LOCAL_BIN}/vineflower"
  chmod +x "${LOCAL_BIN}/vineflower"
  ok "vineflower wrapper created"
fi

# 3. dex2jar (optional) -> persistent $HOME/.local
if [[ ! -x "${LOCAL_BIN}/d2j-dex2jar" ]]; then
  info "downloading dex2jar"
  ZIP="/tmp/dex-tools-v2.4.zip"
  curl -fsSL -o "${ZIP}" "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip" || warn "dex2jar download failed"
  if [[ -f "${ZIP}" ]]; then
    unzip -qo "${ZIP}" -d "${LOCAL_SHARE}/dex2jar"
    D2J_DIR=$(find "${LOCAL_SHARE}/dex2jar" -name d2j-dex2jar.sh -exec dirname {} \; | head -1)
    if [[ -n "${D2J_DIR}" ]]; then
      for s in "${D2J_DIR}"/d2j-*.sh; do
        ln -sf "${s}" "${LOCAL_BIN}/$(basename "${s}" .sh)"
      done
      chmod +x "${D2J_DIR}"/d2j-*.sh
      ok "dex2jar shims installed"
    else
      warn "dex2jar shim dir not found"
    fi
  fi
else
  ok "dex2jar present"
fi

# 4. register [mcp_servers.android_rev_mcp] in .codex/config.toml (idempotent)
if ! grep -q 'mcp_servers.android_rev_mcp' "${CFG}" 2>/dev/null; then
  cat >> "${CFG}" <<CFGEOF

[mcp_servers.android_rev_mcp]
command = "/commands/python3"
args = ["/teamspace/studios/this_studio/android-reverse-engineering-mcp-server/server.py"]
cwd = "/teamspace/studios/this_studio/android-reverse-engineering-mcp-server"
startup_timeout_sec = 120.0

[mcp_servers.android_rev_mcp.env]
HOME = "/teamspace/studios/this_studio"
JAVA_HOME = "/teamspace/studios/this_studio/tools/jdk-21.0.12+8"
FERNFLOWER_JAR_PATH = "/teamspace/studios/this_studio/.local/share/vineflower/vineflower.jar"
PYTHONPATH = "/teamspace/studios/this_studio/android-reverse-engineering-mcp-server/deps"
PATH = "/teamspace/studios/this_studio/.local/bin:/teamspace/studios/this_studio/.revula/bin:/teamspace/studios/this_studio/tools/jdk-21.0.12+8/bin:/home/zeus/miniconda3/envs/cloudspace/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
CFGEOF
  ok "registered [mcp_servers.android_rev_mcp] in ${CFG}"
else
  ok "[mcp_servers.android_rev_mcp] already registered"
fi

# 5. gitignore entries (idempotent)
if ! grep -q '^android-reverse-engineering-mcp-server/$' "${GI}" 2>/dev/null; then
  printf '\n# android-reverse-engineering-mcp-server (embedded repo, managed separately)\nandroid-reverse-engineering-mcp-server/\n' >> "${GI}"
  ok "gitignore updated"
else
  ok "gitignore already set"
fi

echo
echo "=================== ANDROID-REV-MCP SUMMARY ==================="
echo "Server:     [mcp_servers.android_rev_mcp] in ${CFG}"
echo "Deps:       ${MCP}/deps (mcp 1.x pinned)"
echo "Extras:     vineflower + dex2jar in ${LOCAL_BIN}"
echo "Verify:     PYTHONPATH=${MCP}/deps /commands/python3 -c 'import mcp.server.fastmcp'"
echo "================================================================"
