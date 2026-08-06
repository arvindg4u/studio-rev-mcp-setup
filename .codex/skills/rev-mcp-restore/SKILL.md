---
name: rev-mcp-restore
description: Restore and bring up the persistent reverse-engineering MCP stack (revula, ghidra-headless-mcp, jadx-mcp, android-rev-mcp) in this studio after a pool-machine restart. Use when MCP servers are missing, not starting, slow, or tools are unavailable, and when checking what persisted, or re-verifying health of revula/ghidra/jadx/android-rev MCP servers.
---

# Rev MCP Stack Restore (this studio)

After a pool-machine restart only `/teamspace/studios/this_studio` persists. Four MCP servers are configured in `/teamspace/studios/this_studio/.codex/config.toml`:
- `revula` — 121 tools (`python3 -m revula.server`, cwd `revula/`, deps `revula/deps`)
- `ghidra_headless_mcp` — Ghidra 12.1.2 (`tools/ghidra_12.1.2_PUBLIC`, JDK `tools/jdk-21.0.12+8`)
- `jadx_mcp` — 32 tools; requires Xvfb + jadx-gui + the JADX-AI-MCP plugin running
- `android_rev_mcp` — 6 tools (`server.py`, cwd `android-reverse-engineering-mcp-server/`, deps `android-reverse-engineering-mcp-server/deps`, mcp 1.x pinned; optional vineflower/dex2jar in `.local/bin`)

## Hard rules (pool machine)
- NEVER `apt-get install` system-wide. Only deb-extract into `tools/apt` (see `scripts/revula_tools_extras.sh`, `scripts/jadx_mcp_setup.sh`).
- Everything must live under `/teamspace/studios/this_studio`.
- Never use `rm -f` in shell commands (harness filters) — use Python `shutil.rmtree`.
- Embedded repos (`revula/`, `ghidra-headless-mcp/`, `jadx-mcp-server/`) are gitignored; don't commit unless asked.

## Full restore after a restart
1. Run `/teamspace/studios/this_studio/scripts/studio_restore.sh` (revula restore + jadx setup + android-rev setup; idempotent, safe to re-run).
2. Bring up the jadx stack: `/teamspace/studios/this_studio/jadx-mcp-server/bin/jadx-mcp-up` (starts Xvfb :99, jadx-gui with `sample.apk`, waits for plugin on `127.0.0.1:8650`).
3. Start a FRESH Codex session (not `resume`). Codex only attaches MCP servers that reach "ready" at session start; a server spawned mid-session is omitted with `omitting MCP server without an exact ready client`.

## Verify
- revula: call `revula_admin_status`; server log shows `Registered 121 tools` in ~2s.
- ghidra: call any ghidra tool (e.g. `function_list`).
- jadx: `curl -s http://127.0.0.1:8650/all-classes` → JSON class list; then MCP tools like `get_all_classes`, `get_class_source`, `get_android_manifest`.
- android-rev: call `check_dependencies` (expect Java 21, jadx, apktool, adb OK; vineflower + dex2jar optional) and `list_docs`.
- If a server is missing from the session, confirm via `.codex/logs_2.sqlite` (`target='codex_mcp::connection_manager::tool_catalog'`) and require a new session.

## Known pitfalls
- Codex `resume` sessions never re-attach a missed MCP server → new session required.
- jadx-gui must run with `-Duser.home=/teamspace/studios/this_studio` so plugin config/settings persist (launchers do this).
- Xvfb needs the LD_PRELOAD shim `tools/apt/lib/xkbredirect.so` (compiled by setup) because xkbcomp is hardcoded at `/usr/bin/xkbcomp`; without it Xvfb dies with "Failed to activate virtual core keyboard".
- The jadx plugin disables itself in non-GUI mode → always run `jadx-gui` (not the CLI) under Xvfb with an APK loaded.
- `/tmp` scratch does not persist; rebuild test corpora (e.g. `/tmp/revula_tests/`) after a restart.
