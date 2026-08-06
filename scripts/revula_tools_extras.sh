#!/usr/bin/env bash
# =============================================================================
# Revula — persistent EXTRA tools (pool-machine safe)
# -----------------------------------------------------------------------------
# Everything lands under /teamspace/studios/this_studio so it survives studio
# restarts. apt is only used to *download+extract* debs into a local prefix
# (tools/apt) — nothing is installed system-wide.
# Idempotent: safe to re-run any time.
#   SKIP_ANDROID=1 bash scripts/revula_tools_extras.sh   # skip SDK (large)
# =============================================================================
set -uo pipefail

STUDIO="/teamspace/studios/this_studio"
REMCP="${STUDIO}/.revula"
DEPS="${STUDIO}/revula/deps"
BIN="${REMCP}/bin"
JDK="${STUDIO}/tools/jdk-21.0.12+8"
APTROOT="${STUDIO}/tools/apt"
ANDROID_ROOT="${STUDIO}/tools/android"

warn() { echo "[WARN] $*"; }
ok()   { echo "[ OK ] $*"; }
info() { echo "[INFO] $*"; }

mkdir -p "${BIN}" "${APTROOT}" "${ANDROID_ROOT}" "${REMCP}/gems"

# ---------------------------------------------------------------------------
# deb-extraction helper for apt-only tools (persistent local prefix)
# ---------------------------------------------------------------------------
EXCLUDE="^libc6$|^libc6:|^libgcc-s1$|^libstdc\+\+6$|^libcrypt1$|^libc-bin$|^dpkg$|^debconf$|^multiarch-support$|^gcc-|^binutils$|^cpp-"
dep_closure() {
  apt-cache depends --no-recommends --no-suggests --no-conflicts --no-breaks --no-replaces --no-enhances --recurse "$@" 2>/dev/null \
    | sed -n 's/^  Depends: //p' | cut -d' ' -f1 | sort -u
}
deb_extract() {
  local root_pkgs=("$@")
  local tmp; tmp="$(mktemp -d /tmp/debdl.XXXXXX)"
  info "Resolving dependency closure for: ${root_pkgs[*]}"
  local all_pkgs; all_pkgs="$( { printf '%s\n' "${root_pkgs[@]}"; dep_closure "${root_pkgs[@]}"; } | sort -u | grep -vE "$EXCLUDE" )"
  local n=0
  ( cd "$tmp" || exit 1
    while IFS= read -r p; do
      [[ -z "$p" ]] && continue
      if apt-get download "$p" >>/tmp/apt-download.log 2>&1; then n=$((n+1)); else warn "skip pkg: $p"; fi
    done <<< "$all_pkgs"
  )
  info "Downloaded $n debs, extracting into ${APTROOT}"
  for deb in "$tmp"/*.deb; do
    [[ -f "$deb" ]] && dpkg-deb -x "$deb" "$APTROOT" 2>/dev/null || true
  done
  rm -rf "$tmp"
  info "Extracted $(find "$APTROOT/usr/bin" -maxdepth 1 -type f 2>/dev/null | wc -l) binaries"
}

wrapped() { # wrapped <binname> <aptroot-relative-bin-path>
  local name="$1"; local rel="$2"
  {
    echo '#!/usr/bin/env bash'
    printf 'export LD_LIBRARY_PATH="%s/usr/lib/x86_64-linux-gnu:%s/usr/lib:%s/lib/x86_64-linux-gnu:%s/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"\n' "$APTROOT" "$APTROOT" "$APTROOT" "$APTROOT"
    printf 'exec %q "$@"\n' "${APTROOT}/${rel}"
  } > "${BIN}/${name}"
  chmod +x "${BIN}/${name}"
}

# 1. binwalk (deb-extracted: real binwalk + python3-yaml in apt prefix)
if [[ ! -x "${APTROOT}/usr/bin/binwalk" ]]; then
  deb_extract binwalk python3-yaml
fi
if [[ -x "${APTROOT}/usr/bin/binwalk" ]]; then
  cat > "${BIN}/binwalk" <<EOF
#!/usr/bin/env bash
export PYTHONPATH="${APTROOT}/usr/lib/python3/dist-packages\${PYTHONPATH:+:\$PYTHONPATH}"
exec ${APTROOT}/usr/bin/binwalk "\$@"
EOF
  chmod +x "${BIN}/binwalk" && ok "binwalk"
else warn "binwalk missing"; fi

# 2. checksec.sh (single script from upstream)
if [[ ! -x "${BIN}/checksec" ]]; then
  curl -fsSL -o "${BIN}/checksec" "https://raw.githubusercontent.com/slimm609/checksec.sh/main/checksec" 2>/dev/null \
    && chmod +x "${BIN}/checksec" && ok "checksec" || warn "checksec download failed"
fi

# 3. wabt -> wasm2wat
if [[ ! -x "${BIN}/wasm2wat" ]]; then
  deb_extract wabt
  [[ -x "${APTROOT}/usr/bin/wasm2wat" ]] && wrapped wasm2wat usr/bin/wasm2wat && ok "wasm2wat" || warn "wasm2wat missing"
fi

# 4. llvm-19-tools -> llvm-pdbutil
if [[ ! -x "${BIN}/llvm-pdbutil" ]]; then
  deb_extract llvm-19-tools libllvm19
  if [[ -x "${APTROOT}/usr/lib/llvm-19/bin/llvm-pdbutil" ]]; then
    cat > "${BIN}/llvm-pdbutil" <<EOF
#!/usr/bin/env bash
export LD_LIBRARY_PATH="${APTROOT}/usr/lib/x86_64-linux-gnu\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "${APTROOT}/usr/lib/llvm-19/bin/llvm-pdbutil" "\$@"
EOF
    chmod +x "${BIN}/llvm-pdbutil" && ok "llvm-pdbutil"
  else warn "llvm-pdbutil missing"; fi
fi

# 5. wireshark-common + tshark -> tshark, capinfos
if [[ ! -x "${BIN}/tshark" ]]; then
  deb_extract tshark wireshark-common
  [[ -x "${APTROOT}/usr/bin/tshark" ]] && wrapped tshark usr/bin/tshark && ok "tshark" || warn "tshark missing"
  [[ -x "${APTROOT}/usr/bin/capinfos" ]] && wrapped capinfos usr/bin/capinfos && ok "capinfos" || warn "capinfos missing"
fi

# 6. qemu-user + qemu-utils -> qemu-user emulators, qemu-img
if [[ ! -x "${BIN}/qemu-x86_64" ]]; then
  deb_extract qemu-user qemu-utils
  for q in qemu-x86_64 qemu-i386 qemu-aarch64 qemu-arm qemu-mips qemu-mipsel qemu-ppc qemu-ppc64 qemu-riscv32 qemu-riscv64 qemu-sparc qemu-img; do
    if [[ -x "${APTROOT}/usr/bin/${q}" ]]; then wrapped "$q" "usr/bin/${q}"; fi
  done
  [[ -x "${BIN}/qemu-x86_64" ]] && ok "qemu-user/utils" || warn "qemu missing"
fi

# 7. mono -> monodis, ikdasm (.NET IL disassemblers)
if [[ ! -x "${BIN}/monodis" ]]; then
  deb_extract mono-utils mono-runtime mono-common
  [[ -x "${APTROOT}/usr/bin/monodis" ]] && wrapped monodis usr/bin/monodis && ok "monodis" || warn "monodis missing"
  [[ -x "${APTROOT}/usr/bin/ikdasm" ]] && wrapped ikdasm usr/bin/ikdasm && ok "ikdasm" || warn "ikdasm missing"
fi

# 8. ruby 3.2 + one_gadget gem
if [[ ! -x "${BIN}/ruby" ]]; then
  deb_extract ruby3.2 libruby3.2 ruby
  if [[ -x "${APTROOT}/usr/bin/ruby3.2" ]]; then
    cat > "${BIN}/ruby" <<EOF
#!/usr/bin/env bash
export RUBYLIB="${APTROOT}/usr/lib/ruby/3.2.0:${APTROOT}/usr/lib/x86_64-linux-gnu/ruby/3.2.0:${APTROOT}/usr/lib/ruby/vendor_ruby"
export LD_LIBRARY_PATH="${APTROOT}/usr/lib/x86_64-linux-gnu:${APTROOT}/usr/lib:${APTROOT}/lib/x86_64-linux-gnu:${APTROOT}/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "${APTROOT}/usr/bin/ruby3.2" "\$@"
EOF
    chmod +x "${BIN}/ruby" && ok "ruby"
  else warn "ruby missing"; fi
  if [[ -x "${APTROOT}/usr/bin/gem" ]]; then
    cat > "${BIN}/gem" <<EOF
#!/usr/bin/env bash
export GEM_HOME="${REMCP}/gems"
export GEM_PATH="${REMCP}/gems"
export RUBYLIB="${APTROOT}/usr/lib/ruby/3.2.0:${APTROOT}/usr/lib/x86_64-linux-gnu/ruby/3.2.0:${APTROOT}/usr/lib/ruby/vendor_ruby"
export LD_LIBRARY_PATH="${APTROOT}/usr/lib/x86_64-linux-gnu:${APTROOT}/usr/lib:${APTROOT}/lib/x86_64-linux-gnu:${APTROOT}/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export PATH="${BIN}:\${PATH}"
exec "${APTROOT}/usr/bin/gem" "\$@"
EOF
    chmod +x "${BIN}/gem" && ok "gem"
  fi
fi
if [[ ! -x "${BIN}/one_gadget" ]]; then
  if "${BIN}/gem" install one_gadget --install-dir "${REMCP}/gems" --no-document >/dev/null 2>&1; then
    cat > "${BIN}/one_gadget" <<EOF
#!/usr/bin/env bash
export RUBYLIB="${APTROOT}/usr/lib/ruby/3.2.0:${APTROOT}/usr/lib/x86_64-linux-gnu/ruby/3.2.0:${APTROOT}/usr/lib/ruby/vendor_ruby"
export GEM_HOME="${REMCP}/gems"
export GEM_PATH="${REMCP}/gems"
export LD_LIBRARY_PATH="${APTROOT}/usr/lib/x86_64-linux-gnu:${APTROOT}/usr/lib:${APTROOT}/lib/x86_64-linux-gnu:${APTROOT}/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export PATH="${BIN}:\${PATH}"
exec "${REMCP}/gems/bin/one_gadget" "\$@"
EOF
    chmod +x "${BIN}/one_gadget" && ok "one_gadget"
  else warn "one_gadget gem install failed"; fi
fi

# 9. Android SDK (adb, aapt/aapt2, apksigner, zipalign)
if [[ "${SKIP_ANDROID:-0}" != "1" && ! -x "${ANDROID_ROOT}/platform-tools/adb" ]]; then
  if [[ ! -d "${ANDROID_ROOT}/cmdline-tools/latest" ]]; then
    local ct_zip; ct_zip="$(mktemp /tmp/cmdtools.XXXXXX.zip)"
    if curl -fsSL -o "$ct_zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"; then
      mkdir -p "${ANDROID_ROOT}/cmdline-tools"
      unzip -qo "$ct_zip" -d "${ANDROID_ROOT}/cmdline-tools"
      mv "${ANDROID_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_ROOT}/cmdline-tools/latest"
      ok "android cmdline-tools"
    else warn "cmdline-tools download failed"; fi
    rm -f "$ct_zip"
  fi
  if [[ -x "${ANDROID_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]]; then
    export JAVA_HOME="$JDK"
    export PATH="${JDK}/bin:${PATH}"
    yes | "${ANDROID_ROOT}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_ROOT}" --licenses >/dev/null 2>&1 || true
    "${ANDROID_ROOT}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_ROOT}" "platform-tools" "build-tools;34.0.0" >/dev/null 2>&1 \
      && ok "android sdk (platform-tools + build-tools 34)" || warn "sdkmanager install failed"
  fi
fi

# android tool symlinks (build-tools 34 nests tools under android-14/)
BT="${ANDROID_ROOT}/build-tools/34.0.0/android-14"
[[ -x "${ANDROID_ROOT}/platform-tools/adb" ]] && ln -sfn "${ANDROID_ROOT}/platform-tools/adb" "${BIN}/adb"
for t in aapt aapt2 zipalign; do
  [[ -x "${BT}/${t}" ]] && ln -sfn "${BT}/${t}" "${BIN}/${t}"
done
if [[ -x "${BT}/apksigner" ]]; then
  cat > "${BIN}/apksigner-runner" <<EOF
#!/usr/bin/env bash
export JAVA_HOME="${JDK}"
export PATH="\${JAVA_HOME}/bin:\${PATH}"
exec "${BT}/apksigner" "\$@"
EOF
  chmod +x "${BIN}/apksigner-runner"
  ln -sfn "${BIN}/apksigner-runner" "${BIN}/apksigner"
  ok "apksigner"
fi

echo
echo "======================= EXTRAS SUMMARY ======================="
ls -1 "${BIN}" | sort
echo "=============================================================="
