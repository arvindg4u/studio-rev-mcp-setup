# Revula — Codex Setup (persistent, pool-machine safe)

MCP server: https://github.com/president-xd/revula
Pool machine rule: **only `/teamspace/studios/this_studio` survives a restart** — every artifact below lives there. Nothing is installed system-wide (no `apt install`, no venv — the studio blocks venv creation, so deps use `pip install --target`).

Ghidra in revula is **intentionally disabled** — `ghidra-headless-mcp` already provides Ghidra (see `GHIDRA_MCP_SETUP.md`). Revula's decompiler falls back to RetDec (x86/x86-64 works; AArch64 hits an upstream RetDec limitation — use the Ghidra MCP for those).

## Installed components

| Component | Path |
|---|---|
| Revula repo (run via `python3 -m revula.server`, cwd=repo root) | `/teamspace/studios/this_studio/revula` |
| Python deps (full extras: angr, frida, semgrep, quark, floss, capa, pwntools, scapy, tlsh, …) | `/teamspace/studios/this_studio/revula/deps` |
| Tool wrappers (56 entries) | `/teamspace/studios/this_studio/.revula/bin` |
| Tool archives (jadx, apktool, smali, cfr, radare2, rizin, DynamoRIO, DIE, UPX, RetDec, capa-rules, YARA rules) | `/teamspace/studios/this_studio/.revula/tools` |
| Ruby gems (one_gadget) | `/teamspace/studios/this_studio/.revula/gems` |
| Revula config | `/teamspace/studios/this_studio/.revula/config.toml` |
| Deb-extracted toolchains (local prefix: tshark, qemu-user, wabt, llvm-19, mono, ruby, binwalk, **gdb**) | `/teamspace/studios/this_studio/tools/apt` |
| Android SDK (platform-tools + build-tools 34) | `/teamspace/studios/this_studio/tools/android` |
| Shared with ghidra MCP: Ghidra 12.1.2, Temurin JDK 21 | `/teamspace/studios/this_studio/tools/ghidra_12.1.2_PUBLIC`, `/teamspace/studios/this_studio/tools/jdk-21.0.12+8` |

## Tool status

- 121 MCP tools registered under the `revula_` namespace (`re_*` internal names are namespaced; no clash with `ghidra_headless_mcp`).
- 41/44 external tools available (gdb now persistent, no system-wide apt install). Missing (optional): `lldb`, `msfvenom`, `qemu_system` (multi-GB system emulator) — revula degrades gracefully.
- Key backends verified through Codex: entropy, disasm (r2/capstone), strings (floss/strings), parse_binary, binwalk firmware scan, capa (rules bundled), RetDec decompile (x86), admin status.

## Wrapper fixes baked in (do not regress these)

- `apktool` / `cfr` — single `exec java -jar …` (previous multi-`exec` versions were broken).
- `retdec-decompiler` — runner adds `--no-memory-limit`; RetDec's own `setrlimit(RAM/2)` fails under revula's subprocess memory cap.
- `capa` — auto-passes `-r …/.revula/tools/capa_rules` (pip capa ships no embedded rules).
- `smali`/`baksmali` — Maven dependency jars on classpath (jcommander 1.64, dexlib2, util, antlr, stringtemplate, guava-android).
- `r2`/`radare2`/`diec`/`apksigner`/`tshark`/`qemu-*`/`monodis`/`ikdasm`/`llvm-pdbutil` — runner scripts set `LD_LIBRARY_PATH`/`JAVA_HOME`/`PYTHONPATH` as needed; deb-extracted binaries are never edited through symlinks.
- `ruby`/`gem`/`one_gadget` — `RUBYLIB`, `GEM_HOME`/`GEM_PATH` to the persistent gem dir.

## Restore after a studio restart

```bash
cd /teamspace/studios/this_studio
bash scripts/revula_restore.sh            # main + extras + MCP registration (idempotent)
# faster: SKIP_PIP=1 SKIP_ANDROID=1 bash scripts/revula_restore.sh
```

Individual scripts:

- `scripts/revula_persistent_setup.sh` — repo clone, `pip install --target … ".[full]"`, core tools, wrappers, `config.toml`.
- `scripts/revula_tools_extras.sh` — apt-prefix tools (binwalk, tshark, qemu-user, wabt, llvm-19, mono, ruby, **gdb** + one_gadget), Android SDK.
- `scripts/revula_codex_mcp.sh` — (re)writes the `[mcp_servers.revula]` block in `.codex/config.toml`; validates TOML before replacing.

## Codex registration

`[mcp_servers.revula]` runs `/commands/python3 -m revula.server` with `cwd` = repo root,
`PYTHONPATH` = `…/revula/deps`, `PATH` prepends `…/.revula/bin` + JDK bin, `HOME` = `/this_studio`.
**No `GHIDRA_HEADLESS`/`GHIDRA_INSTALL_DIR`** (Ghidra disabled in revula).

## Verify

```bash
codex mcp list
codex exec --skip-git-repo-check "Call revula_admin_status and report the tool count"
codex exec --skip-git-repo-check "Call revula_entropy on /teamspace/studios/this_studio/ghidra-headless-mcp/samples/ls"
```

Notes: first call after a session starts pays JVM/backend boot. Ghidra work belongs to the
`ghidra_headless_mcp` server; revula covers everything else.
