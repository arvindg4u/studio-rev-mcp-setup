# Ghidra Headless MCP — Codex Setup

MCP server: https://github.com/mrphrazer/ghidra-headless-mcp
This studio is a pool machine: only `/teamspace/studios/this_studio` persists across restarts,
so everything lives there.

## Installed components

| Component | Path |
|---|---|
| MCP server repo | `/teamspace/studios/this_studio/ghidra-headless-mcp` |
| Python deps (pyghidra, jpype) | `/teamspace/studios/this_studio/ghidra-headless-mcp/deps` |
| Ghidra 12.1.2 | `/teamspace/studios/this_studio/tools/ghidra_12.1.2_PUBLIC` |
| Temurin JDK 21 | `/teamspace/studios/this_studio/tools/jdk-21.0.12+8` |
| Codex MCP config | `/teamspace/studios/this_studio/.codex/config.toml` (backup: `.bak-ghidra`) |

The studio blocks `venv` creation, so deps are installed with `pip install --target`:

```bash
python3 -m pip install --target=/teamspace/studios/this_studio/ghidra-headless-mcp/deps \
  /teamspace/studios/this_studio/ghidra-headless-mcp
```

## Codex registration

Registered as `ghidra_headless_mcp` (stdio, real Ghidra backend) in `.codex/config.toml`:

```toml
[mcp_servers.ghidra_headless_mcp]
command = "/commands/python3"
args = ["/teamspace/studios/this_studio/ghidra-headless-mcp/ghidra_headless_mcp.py",
        "--ghidra-install-dir", "/teamspace/studios/this_studio/tools/ghidra_12.1.2_PUBLIC"]
cwd = "/teamspace/studios/this_studio/ghidra-headless-mcp"
startup_timeout_sec = 180

[mcp_servers.ghidra_headless_mcp.env]
GHIDRA_INSTALL_DIR = "/teamspace/studios/this_studio/tools/ghidra_12.1.2_PUBLIC"
JAVA_HOME = "/teamspace/studios/this_studio/tools/jdk-21.0.12+8"
PYTHONPATH = "/teamspace/studios/this_studio/ghidra-headless-mcp/deps"
HOME = "/teamspace/studios/this_studio"
```

## Verify

```bash
codex mcp list                        # server shows "enabled"
codex exec --skip-git-repo-check "Call the ghidra_headless_mcp health.ping tool"
```

First tool call after a session starts takes ~20–30 s (JVM boot). Use `program.open` with a binary
path, then `decomp.function` / `search.*` / `analysis.*` tools (212 tools total).

## After a studio restart

`/this_studio` persists, so the repo, deps, Ghidra, JDK, and config should all survive. If the
conda Python is gone (base image re-provisioned), reinstall it, then re-run the `pip install
--target` command above. Nothing else needs reconfiguring.

## Updating the server

```bash
cd /teamspace/studios/this_studio/ghidra-headless-mcp
git pull
python3 -m pip install --target=/teamspace/studios/this_studio/ghidra-headless-mcp/deps .   # refresh deps
```
