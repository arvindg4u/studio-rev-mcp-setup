#!/usr/bin/env bash
# =============================================================================
# Revula — persistent setup (pool-machine safe)
# -----------------------------------------------------------------------------
# EVERYTHING this script writes lives under /teamspace/studios/this_studio
# (the only path that survives a studio restart). No apt installs, no
# system-wide writes.
#
# Ghidra integration in revula is intentionally DISABLED: ghidra-headless-mcp
# already provides Ghidra (shared install at tools/ghidra_12.1.2_PUBLIC).
# revula's decompiler falls back to RetDec (see tools/retdec wrapper).
#
# Idempotent: safe to re-run any time; existing pieces are kept.
#   SKIP_PIP=1 bash scripts/revula_persistent_setup.sh   # skip the big pip step
# =============================================================================
set -uo pipefail

STUDIO="/teamspace/studios/this_studio"
REMCP="${STUDIO}/.revula"
REPO="${STUDIO}/revula"
DEPS="${REPO}/deps"
TOOLS="${REMCP}/tools"
BIN="${REMCP}/bin"
JDK="${STUDIO}/tools/jdk-21.0.12+8"
JAVA_BIN="${JDK}/bin/java"
REPO_URL="https://github.com/president-xd/revula"

mkdir -p "${REMCP}" "${TOOLS}" "${BIN}" "${REMCP}/cache" "${REMCP}/ghidra_projects" "${REMCP}/yara_rules"

warn() { echo "[WARN] $*"; }
ok()   { echo "[ OK ] $*"; }
info() { echo "[INFO] $*"; }

dl() { curl -fSL --retry 3 -o "$1" "$2" 2>/dev/null; }

find_bin() {
  local root="$1"; shift
  local name found
  for name in "$@"; do
    found="$(find "$root" -type f -name "$name" -perm -u+x 2>/dev/null | head -1)"
    if [[ -n "$found" ]]; then echo "$found"; return 0; fi
  done
  return 1
}

write_script() { # write_script <name>   (content on stdin)
  local name="$1"
  cat > "${BIN}/${name}"
  chmod +x "${BIN}/${name}"
}

# Python-CLI wrapper (deps/bin/<name> with PYTHONPATH set)
pywrap() {
  local name="$1"; shift
  {
    echo '#!/usr/bin/env bash'
    printf 'export PYTHONPATH="%s${PYTHONPATH:+:$PYTHONPATH}"\n' "${DEPS}"
    printf '%s\n' "$@"
    printf 'exec "%s/bin/%s" "$@"\n' "${DEPS}" "${name}"
  } | write_script "${name}"
}

info "== Step 0: revula repo =="
if [[ ! -d "${REPO}/.git" ]]; then
  git clone --depth 1 "${REPO_URL}" "${REPO}" && ok "cloned revula" || warn "revula clone failed"
fi

info "== Step 1: Python deps (persistent, ~2-4 GB) =="
if [[ "${SKIP_PIP:-0}" != "1" && ! -d "${DEPS}/angr" ]]; then
  if [[ -d "${REPO}" ]]; then
    ( cd "${REPO}" && python3 -m pip install --upgrade pip -q && python3 -m pip install --target="${DEPS}" ".[full]" ) \
      && ok "python full deps" || warn "pip install failed (run again later)"
  fi
fi

info "== Step 2: core tool downloads =="
# --- jadx ----------------------------------------------------------------
if [[ ! -x "${TOOLS}/jadx/bin/jadx" ]]; then
  local_tmp="$(mktemp /tmp/jadx.XXXXXX.zip)"
  if dl "$local_tmp" "https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip"; then
    mkdir -p "${TOOLS}/jadx" && unzip -qo "$local_tmp" -d "${TOOLS}/jadx" && chmod +x "${TOOLS}/jadx/bin/jadx" && ok "jadx"
  else warn "jadx download failed"; fi
  rm -f "$local_tmp"
fi

# --- apktool -------------------------------------------------------------
if [[ ! -f "${TOOLS}/apktool/apktool.jar" ]]; then
  mkdir -p "${TOOLS}/apktool"
  if dl "${TOOLS}/apktool/apktool.jar" "https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar"; then
    ok "apktool"
  else warn "apktool download failed"; fi
fi

# --- smali / baksmali (jar + maven deps needed on the classpath) ---------
if [[ ! -f "${TOOLS}/smali/smali.jar" ]]; then
  mkdir -p "${TOOLS}/smali"
  if dl "${TOOLS}/smali/smali.jar" "https://repo.maven.apache.org/maven2/org/smali/smali/2.5.2/smali-2.5.2.jar" \
     && dl "${TOOLS}/smali/baksmali.jar" "https://repo.maven.apache.org/maven2/org/smali/baksmali/2.5.2/baksmali-2.5.2.jar"; then
    ok "smali/baksmali"
  else warn "smali/baksmali download failed"; fi
fi
declare -A SMALI_DEPS=(
  [jcommander-1.64.jar]="https://repo.maven.apache.org/maven2/com/beust/jcommander/1.64/jcommander-1.64.jar"
  [dexlib2-2.5.2.jar]="https://repo.maven.apache.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar"
  [util-2.5.2.jar]="https://repo.maven.apache.org/maven2/org/smali/util/2.5.2/util-2.5.2.jar"
  [antlr-3.5.2.jar]="https://repo.maven.apache.org/maven2/org/antlr/antlr/3.5.2/antlr-3.5.2.jar"
  [antlr-runtime-3.5.2.jar]="https://repo.maven.apache.org/maven2/org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar"
  [stringtemplate-3.2.1.jar]="https://repo.maven.apache.org/maven2/org/antlr/stringtemplate/3.2.1/stringtemplate-3.2.1.jar"
  [guava-27.1-android.jar]="https://repo.maven.apache.org/maven2/com/google/guava/guava/27.1-android/guava-27.1-android.jar"
)
for jar in "${!SMALI_DEPS[@]}"; do
  if [[ ! -f "${TOOLS}/smali/${jar}" ]]; then
    dl "${TOOLS}/smali/${jar}" "${SMALI_DEPS[$jar]}" || warn "smali dep ${jar} failed"
  fi
done

# --- CFR ------------------------------------------------------------------
if [[ ! -f "${TOOLS}/cfr/cfr.jar" ]]; then
  mkdir -p "${TOOLS}/cfr"
  if dl "${TOOLS}/cfr/cfr.jar" "https://www.benf.org/other/cfr/cfr-0.152.jar"; then ok "cfr"; else warn "cfr download failed"; fi
fi

# --- radare2 (local deb extract, no dpkg -i) -------------------------------
if [[ ! -x "${TOOLS}/radare2/usr/bin/r2" ]]; then
  local_tmp="$(mktemp /tmp/radare2.XXXXXX.deb)"
  if dl "$local_tmp" "https://github.com/radareorg/radare2/releases/download/6.1.2/radare2_6.1.2_amd64.deb"; then
    mkdir -p "${TOOLS}/radare2" && dpkg-deb -x "$local_tmp" "${TOOLS}/radare2" && ok "radare2"
  else warn "radare2 download failed"; fi
  rm -f "$local_tmp"
fi

# --- rizin (static bundle) --------------------------------------------------
if [[ ! -x "${TOOLS}/rizin/bin/rizin" ]]; then
  local_tmp="$(mktemp /tmp/rizin.XXXXXX.tar.xz)"
  if dl "$local_tmp" "https://github.com/rizinorg/rizin/releases/download/v0.8.2/rizin-v0.8.2-static-x86_64.tar.xz"; then
    mkdir -p "${TOOLS}/rizin" && tar -xf "$local_tmp" -C "${TOOLS}/rizin" && ok "rizin"
  else warn "rizin download failed"; fi
  rm -f "$local_tmp"
fi

# --- DynamoRIO ---------------------------------------------------------------
if [[ ! -x "${TOOLS}/dynamorio/bin64/drrun" && ! -x "${TOOLS}/dynamorio/dynamorio/bin64/drrun" ]]; then
  local_tmp="$(mktemp /tmp/dynamorio.XXXXXX.tar.gz)"
  if dl "$local_tmp" "https://github.com/DynamoRIO/dynamorio/releases/download/cronbuild-11.91.20545/DynamoRIO-Linux-11.91.20545.tar.gz"; then
    mkdir -p "${TOOLS}/dynamorio" && tar -xzf "$local_tmp" -C "${TOOLS}/dynamorio" && ok "dynamorio"
  else warn "dynamorio download failed"; fi
  rm -f "$local_tmp"
fi

# --- Detect It Easy (local deb extract) --------------------------------------
if [[ ! -x "${TOOLS}/die/usr/bin/diec" ]]; then
  local_tmp="$(mktemp /tmp/die.XXXXXX.deb)"
  if dl "$local_tmp" "https://github.com/horsicq/DIE-engine/releases/download/3.10/die_3.10_Debian_12_amd64.deb"; then
    mkdir -p "${TOOLS}/die" && dpkg-deb -x "$local_tmp" "${TOOLS}/die" && ok "diec"
  else warn "diec download failed"; fi
  rm -f "$local_tmp"
fi

# --- UPX ----------------------------------------------------------------------
upx_guard="$(find_bin "${TOOLS}/upx" upx || true)"
if [[ -z "$upx_guard" ]]; then
  local_tmp="$(mktemp /tmp/upx.XXXXXX.tar.xz)"
  if dl "$local_tmp" "https://github.com/upx/upx/releases/download/v5.1.1/upx-5.1.1-amd64_linux.tar.xz"; then
    mkdir -p "${TOOLS}/upx" && tar -xf "$local_tmp" -C "${TOOLS}/upx" && chmod +x "$(find_bin "${TOOLS}/upx" upx)" && ok "upx"
  else warn "upx download failed"; fi
  rm -f "$local_tmp"
fi

# --- RetDec ---------------------------------------------------------------------
if [[ ! -x "${TOOLS}/retdec/bin/retdec-decompiler" ]]; then
  local_tmp="$(mktemp /tmp/retdec.XXXXXX.tar.xz)"
  if dl "$local_tmp" "https://github.com/avast/retdec/releases/download/v5.0/RetDec-v5.0-Linux-Release.tar.xz"; then
    mkdir -p "${TOOLS}/retdec" && tar -xf "$local_tmp" -C "${TOOLS}/retdec" && ok "retdec"
  else warn "retdec download failed"; fi
  rm -f "$local_tmp"
fi

# --- YARA community rules ----------------------------------------------------------
if [[ -z "$(find "${REMCP}/yara_rules" -name '*.yar*' 2>/dev/null | head -1)" ]]; then
  local_tmp="$(mktemp /tmp/yara.XXXXXX.zip)"
  if dl "$local_tmp" "https://github.com/Yara-Rules/rules/archive/refs/heads/master.zip"; then
    local ext_dir; ext_dir="$(mktemp -d /tmp/yara_extract.XXXXXX)"
    unzip -qo "$local_tmp" -d "$ext_dir" && cp -r "$ext_dir"/rules-master/* "${REMCP}/yara_rules/"
    rm -rf "$ext_dir" && ok "yara rules"
  else warn "yara rules download failed"; fi
  rm -f "$local_tmp"
fi

# --- capa rules (persistent) -------------------------------------------------------
if [[ ! -d "${TOOLS}/capa_rules/.git" ]]; then
  git clone --depth 1 https://github.com/mandiant/capa-rules "${TOOLS}/capa_rules" && ok "capa rules" || warn "capa rules clone failed"
fi

info "== Step 3: wrappers (always refreshed to known-good state) =="
# java-based (single exec; previous multi-exec versions were broken)
write_script apktool <<EOF
#!/usr/bin/env bash
exec "${JAVA_BIN}" -jar "${TOOLS}/apktool/apktool.jar" "\$@"
EOF
write_script cfr <<EOF
#!/usr/bin/env bash
exec "${JAVA_BIN}" -jar "${TOOLS}/cfr/cfr.jar" "\$@"
EOF
SMALI_CP="${TOOLS}/smali/smali.jar:${TOOLS}/smali/dexlib2-2.5.2.jar:${TOOLS}/smali/util-2.5.2.jar:${TOOLS}/smali/antlr-3.5.2.jar:${TOOLS}/smali/antlr-runtime-3.5.2.jar:${TOOLS}/smali/jcommander-1.64.jar:${TOOLS}/smali/stringtemplate-3.2.1.jar:${TOOLS}/smali/guava-27.1-android.jar"
write_script smali <<EOF
#!/usr/bin/env bash
exec "${JAVA_BIN}" -cp "${SMALI_CP}" org.jf.smali.Main "\$@"
EOF
BAKSMALI_CP="${TOOLS}/smali/baksmali.jar:${TOOLS}/smali/dexlib2-2.5.2.jar:${TOOLS}/smali/util-2.5.2.jar:${TOOLS}/smali/antlr-3.5.2.jar:${TOOLS}/smali/antlr-runtime-3.5.2.jar:${TOOLS}/smali/jcommander-1.64.jar:${TOOLS}/smali/stringtemplate-3.2.1.jar:${TOOLS}/smali/guava-27.1-android.jar"
write_script baksmali <<EOF
#!/usr/bin/env bash
exec "${JAVA_BIN}" -cp "${BAKSMALI_CP}" org.jf.baksmali.Main "\$@"
EOF

# radare2 (needs its own lib dir from the deb extraction)
write_script r2-runner <<EOF
#!/usr/bin/env bash
export LD_LIBRARY_PATH="${TOOLS}/radare2/usr/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "${TOOLS}/radare2/usr/bin/radare2" "\$@"
EOF
ln -sfn "${BIN}/r2-runner" "${BIN}/r2"
ln -sfn "${BIN}/r2-runner" "${BIN}/radare2"

# rizin family
for tool in rizin rz rz-bin rz-diff rz-asm rz-gg; do
  found="$(find_bin "${TOOLS}/rizin" "$tool" || true)"
  [[ -n "$found" ]] && ln -sfn "$found" "${BIN}/$tool"
done

# jadx / upx / drrun
[[ -x "${TOOLS}/jadx/bin/jadx" ]] && ln -sfn "${TOOLS}/jadx/bin/jadx" "${BIN}/jadx"
upx_bin="$(find_bin "${TOOLS}/upx" upx || true)"
[[ -n "$upx_bin" ]] && ln -sfn "$upx_bin" "${BIN}/upx"
drrun_bin="$(find_bin "${TOOLS}/dynamorio" drrun || true)"
if [[ -n "$drrun_bin" ]]; then
  write_script drrun <<EOF
#!/usr/bin/env bash
exec ${drrun_bin} "\$@"
EOF
fi

# retdec — runner wrapper: RetDec crashes trying to setrlimit(RAM/2) in this
# container, so --no-memory-limit is mandatory.
write_script retdec-decompiler <<EOF
#!/usr/bin/env bash
exec "${TOOLS}/retdec/bin/retdec-decompiler" --no-memory-limit "\$@"
EOF

# diec — needs Qt libs extracted next to it
write_script diec-runner <<EOF
#!/usr/bin/env bash
export LD_LIBRARY_PATH="${TOOLS}/die/usr/lib/x86_64-linux-gnu\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "${TOOLS}/die/usr/bin/diec" "\$@"
EOF
ln -sfn "${BIN}/diec-runner" "${BIN}/diec"

# python CLIs
pywrap floss
pywrap ROPgadget
pywrap ropper
pywrap pwn
pywrap semgrep
pywrap pysemgrep
pywrap quark
write_script capa <<EOF
#!/usr/bin/env bash
export PYTHONPATH="${DEPS}\${PYTHONPATH:+:\$PYTHONPATH}"
CAPA_RULES="${TOOLS}/capa_rules"
if [[ -d "\$CAPA_RULES" ]]; then
  for arg in "\$@"; do
    if [[ "\$arg" == "-r" || "\$arg" == "--rules" ]]; then
      exec "${DEPS}/bin/capa" "\$@"
    fi
  done
  exec "${DEPS}/bin/capa" -r "\$CAPA_RULES" "\$@"
fi
exec "${DEPS}/bin/capa" "\$@"
EOF

info "== Step 4: config.toml =="
cat > "${REMCP}/config.toml" <<TOML
# Generated by revula_persistent_setup.sh — all paths are persistent under /this_studio
# NOTE: Ghidra is intentionally absent — ghidra-headless-mcp provides Ghidra.

[tools.java]
path = "${JAVA_BIN}"

[tools.radare2]
path = "${BIN}/r2"

[tools.rizin]
path = "${BIN}/rizin"

[tools.rz_diff]
path = "${BIN}/rz-diff"

[tools.jadx]
path = "${BIN}/jadx"

[tools.apktool]
path = "${BIN}/apktool"

[tools.smali]
path = "${BIN}/smali"

[tools.baksmali]
path = "${BIN}/baksmali"

[tools.cfr]
path = "${BIN}/cfr"

[tools.retdec_decompiler]
path = "${BIN}/retdec-decompiler"

[tools.upx]
path = "${BIN}/upx"

[tools.diec]
path = "${BIN}/diec"

[tools.drrun]
path = "${BIN}/drrun"

[tools.floss]
path = "${BIN}/floss"

[tools.capa]
path = "${BIN}/capa"

[tools.ropgadget]
path = "${BIN}/ROPgadget"

[tools.pwn]
path = "${BIN}/pwn"

[tools.semgrep]
path = "${BIN}/semgrep"

[tools.quark]
path = "${BIN}/quark"

[tools.monodis]
path = "${BIN}/monodis"

[tools.ikdasm]
path = "${BIN}/ikdasm"

[tools.one_gadget]
path = "${BIN}/one_gadget"

[tools.binwalk]
path = "${BIN}/binwalk"

[tools.capinfos]
path = "${BIN}/capinfos"

[tools.checksec]
path = "${BIN}/checksec"

[tools.gdb]
path = "${BIN}/gdb"

[tools.pdbutil]
path = "${BIN}/llvm-pdbutil"

[tools.qemu_user]
path = "${BIN}/qemu-x86_64"

[tools.qemu_img]
path = "${BIN}/qemu-img"

[tools.tshark]
path = "${BIN}/tshark"

[tools.wasm2wat]
path = "${BIN}/wasm2wat"

[tools.aapt]
path = "${BIN}/aapt"

[tools.adb]
path = "${BIN}/adb"

[tools.apksigner]
path = "${BIN}/apksigner"

[security]
max_memory_mb = 2048
default_timeout = 120
max_timeout = 900
allowed_dirs = ["/teamspace/studios/this_studio", "/tmp", "/home/zeus"]

[rate_limit]
enabled = true
global_rpm = 240
per_tool_rpm = 60
burst_size = 20

[tool_naming]
namespace = "revula"
include_legacy_names = false

[execution]
subprocess_retries = 1
subprocess_retry_backoff_ms = 250
TOML
ok "config.toml written (no ghidra section)"

echo
echo "======================= INSTALL SUMMARY ======================="
echo "Persistent tool dir:  ${REMCP}"
echo "Python deps:          ${DEPS}"
echo "Config:               ${REMCP}/config.toml"
echo "Next:                 bash scripts/revula_tools_extras.sh"
echo "                      bash scripts/revula_codex_mcp.sh"
echo "================================================================"
