"""
MCP host configuration updater
================================
Updates credentials in the MCP server entry and prints the command needed to
apply them, handling three server deployment modes:

  stdio-docker  — Claude Desktop launches the container itself via
                  `docker run -i --rm -e KEY=VAL … image`.
                  Credentials live in the `args` list.
                  → Update args in-place, print docker command, restart Claude.

  sse-docker    — A persistent container exposes an HTTP SSE endpoint.
                  Claude Desktop connects via `{"url": "http://localhost:8081/sse"}`.
                  Credentials are only inside the container's environment.
                  → Print the `docker run -d` command; no Claude restart needed.

  sse           — A server exposes an HTTP SSE endpoint. Claude Desktop connects
                  via `{"url": "http://localhost:8081/sse"}`. The server can be
                  started any way: Docker, IDE main method, or java -jar.
                  → Print env vars (for IDE/local) and docker command (as one option).

Mode is detected automatically from the existing Claude Desktop config entry,
falling back to the MCP_SERVER_PROTOCOL / SPRING_PROFILES_ACTIVE env vars.
"""

from __future__ import annotations

import json
import os
import platform
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Callable


# ── Session file ───────────────────────────────────────────────────────────────

_SESSION_FILE = Path.home() / ".broker-mcp" / ".env.session"


def write_session_keys(new_keys: dict[str, str]) -> None:
    """Merge *new_keys* into the shared session file, preserving existing keys."""
    _SESSION_FILE.parent.mkdir(parents=True, exist_ok=True)
    existing: dict[str, str] = {}
    if _SESSION_FILE.exists():
        for line in _SESSION_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                existing[k.strip()] = v.strip()
    existing.update(new_keys)
    lines = [f"# Auto-generated — last updated {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"]
    lines.extend(f"{k}={v}" for k, v in sorted(existing.items()))
    _SESSION_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ── Platform-aware config paths ────────────────────────────────────────────────

def _system() -> str:
    return platform.system()


def _claude_desktop_path() -> Path | None:
    if _system() == "Darwin":
        return Path.home() / "Library" / "Application Support" / "Claude" / "claude_desktop_config.json"
    if _system() == "Windows":
        appdata = os.environ.get("APPDATA")
        if appdata:
            return Path(appdata) / "Claude" / "claude_desktop_config.json"
    return None


def _codex_path() -> Path | None:
    if _system() in ("Darwin", "Linux"):
        return Path.home() / ".codex" / "config.json"
    if _system() == "Windows":
        home = os.environ.get("USERPROFILE") or os.environ.get("HOMEPATH")
        if home:
            return Path(home) / ".codex" / "config.json"
    return None


_HOST_RESOLVERS: list[tuple[str, Callable[[], Path | None], bool]] = [
    ("Claude Desktop", _claude_desktop_path, True),
    ("Codex",          _codex_path,          False),
]


# ── JSON helpers ───────────────────────────────────────────────────────────────

def _read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


# ── Docker args helpers ────────────────────────────────────────────────────────

# Env vars whose values are host filesystem paths that must be volume-mounted
# into the container.  Maps env-var name → canonical container directory.
# Derived from application-sse.properties defaults.
_DOCKER_PATH_ENV: dict[str, str] = {
    "ZERODHA_TRADEBOOK_IMPORT_ROOT":    "/imports",
    "ZERODHA_TRADEBOOK_STORE_PATH":      "/data",
}


def _parse_docker_env(args: list[str]) -> dict[str, str]:
    """Return {KEY: VALUE} for every ``-e KEY=VALUE`` pair in a docker args list."""
    env: dict[str, str] = {}
    i = 0
    while i < len(args):
        if args[i] == "-e" and i + 1 < len(args) and "=" in args[i + 1]:
            k, v = args[i + 1].split("=", 1)
            env[k] = v
            i += 2
        else:
            i += 1
    return env


def _parse_docker_volumes(args: list[str]) -> dict[str, str]:
    """Return {host_path: container_path} for every ``-v host:container`` pair."""
    vols: dict[str, str] = {}
    i = 0
    while i < len(args):
        if args[i] == "-v" and i + 1 < len(args):
            parts = args[i + 1].split(":")
            if len(parts) >= 2:
                vols[parts[0]] = parts[1]
            i += 2
        else:
            i += 1
    return vols


def _to_container_path(host_value: str, container_dir: str, volumes: dict[str, str]) -> tuple[str, str | None]:
    """
    Translate a host path value to its container equivalent.

    Returns (container_value, volume_spec_to_add_or_None).
    volume_spec_to_add is ``"host_dir:container_dir"`` when a new mount is needed.
    """
    host_path = Path(host_value)
    filename = host_path.name if host_path.suffix else None  # is it a file path?

    # Determine the host directory that should be mounted.
    host_dir = str(host_path) if not filename else str(host_path.parent)

    # Check if a volume already covers this host dir.
    for mounted_host, mounted_container in volumes.items():
        if host_dir == mounted_host or host_dir.startswith(mounted_host + "/"):
            relative = Path(host_dir).relative_to(mounted_host)
            base = (Path(mounted_container) / relative).as_posix()
            result = f"{base}/{filename}" if filename else base
            return result, None  # volume already present

    # No existing mount — need to add one.
    container_value = f"{container_dir}/{filename}" if filename else container_dir
    volume_spec = f"{host_dir}:{container_dir}"
    return container_value, volume_spec


def _update_docker_env(args: list[str], env_values: dict[str, str]) -> list[str]:
    """Update existing ``-e KEY=VALUE`` pairs and append any new ones.

    For env vars that represent filesystem paths (defined in ``_DOCKER_PATH_ENV``),
    the host path is translated to the container-side path and the required
    ``-v host:container`` volume mount is added when absent.
    """
    volumes = _parse_docker_volumes(args)
    new_volumes: list[str] = []

    # Translate path values for well-known path env vars.
    translated: dict[str, str] = {}
    for k, v in env_values.items():
        if k in _DOCKER_PATH_ENV and Path(v).is_absolute():
            container_dir = _DOCKER_PATH_ENV[k]
            container_val, vol_spec = _to_container_path(v, container_dir, volumes)
            translated[k] = container_val
            if vol_spec and vol_spec not in new_volumes:
                new_volumes.append(vol_spec)
        else:
            translated[k] = v

    updated: set[str] = set()
    i = 0
    while i < len(args):
        if args[i] == "-e" and i + 1 < len(args) and "=" in args[i + 1]:
            k, _ = args[i + 1].split("=", 1)
            if k in translated:
                args[i + 1] = f"{k}={translated[k]}"
                updated.add(k)
            i += 2
        else:
            i += 1

    for k, v in translated.items():
        if k not in updated:
            args.insert(len(args) - 1, "-e")
            args.insert(len(args) - 1, f"{k}={v}")

    # Add missing volume mounts before the image name (last positional arg).
    for vol_spec in new_volumes:
        host_dir = vol_spec.split(":")[0]
        if host_dir not in volumes:
            args.insert(len(args) - 1, "-v")
            args.insert(len(args) - 1, vol_spec)
            print(f"   ➕ Added volume mount: -v {vol_spec}")

    return args


def _format_docker_command(command: str, args: list[str]) -> str:
    """Return a pretty-printed ``docker run …`` string."""
    parts: list[str] = []
    i = 0
    while i < len(args):
        if args[i] in ("-e", "-v", "-p", "--name", "--network") and i + 1 < len(args):
            parts.append(f"  {args[i]} {args[i + 1]}")
            i += 2
        else:
            parts.append(f"  {args[i]}")
            i += 1
    return command + " \\\n" + " \\\n".join(parts)


# ── Server discovery ───────────────────────────────────────────────────────────

def _entry_mode(server: dict) -> str:
    """Return 'env-block', 'docker-args', 'sse-url', or 'unknown'."""
    if "url" in server:
        return "sse-url"
    if server.get("command") == "docker" and "run" in server.get("args", []):
        return "docker-args"
    if "env" in server:
        return "env-block"
    return "unknown"


def _find_server_name(config: dict, probe_keys: list[str]) -> str | None:
    for name, server in config.get("mcpServers", {}).items():
        mode = _entry_mode(server)
        if mode == "env-block":
            if any(k in server.get("env", {}) for k in probe_keys):
                return name
        elif mode == "docker-args":
            if any(k in _parse_docker_env(server.get("args", [])) for k in probe_keys):
                return name
        elif mode == "sse-url":
            # SSE entries have no credentials — match by server name heuristic
            # (e.g. name contains "breeze" or "zerodha")
            if any(hint in name.lower() for hint in ("breeze", "zerodha", "mcp")):
                return name
    return None


# ── Claude Desktop restart ─────────────────────────────────────────────────────

def _restart_claude_desktop() -> None:
    print("\n🔄 Restarting Claude Desktop to pick up new credentials...")
    system = _system()
    try:
        if system == "Darwin":
            subprocess.run(
                ["osascript", "-e", 'tell application "Claude" to quit'],
                check=False, capture_output=True,
            )
            time.sleep(2)
            subprocess.run(["open", "-a", "Claude"], check=False, capture_output=True)
            print("   ✅ Claude Desktop restarted.")
        elif system == "Windows":
            subprocess.run(["taskkill", "/IM", "Claude.exe", "/F"], check=False, capture_output=True)
            time.sleep(2)
            subprocess.run(["start", "Claude"], shell=True, check=False)
            print("   ✅ Claude Desktop restarted.")
        else:
            print("   ℹ️  Auto-restart not supported on Linux — restart Claude Desktop manually.")
    except Exception as exc:
        print(f"   ⚠️  Could not restart Claude Desktop: {exc}\n   Restart it manually.")


# ── SSE / IDE instructions ─────────────────────────────────────────────────────

def _print_sse_instructions(env_values: dict[str, str], image: str = "broker-mcp") -> None:
    print("\n🔑 Environment variables to apply (IDE run config / docker -e flags):")
    for k, v in env_values.items():
        print(f"   {k}={v}")

    env_flags = " \\\n".join(f"  -e {k}={v}" for k, v in env_values.items())
    print(
        f"\n🐳 Or restart via Docker:\n\n"
        f"  docker stop {image} 2>/dev/null; docker rm {image} 2>/dev/null\n"
        f"  docker run -d \\\n"
        f"  --name {image} \\\n"
        f"  -p 8081:8081 \\\n"
        f"{env_flags} \\\n"
        f"  {image}\n\n"
        f"  ℹ️  No Claude restart needed — the SSE URL stays the same."
    )


# ── Public API ─────────────────────────────────────────────────────────────────

def update_mcp_env(
    probe_keys: list[str],
    env_values: dict[str, str],
    restart: bool = True,
) -> None:
    """
    Locate the MCP server entry in every supported host config and update it.

    Handles stdio-docker (``-e`` flags in args), env-block, SSE URL, and IDE
    mode entries. Prints the docker command in all cases for easy reference.
    """
    updated_any = False
    restart_claude = False

    for host_name, resolve_path, supports_restart in _HOST_RESOLVERS:
        config_path = resolve_path()
        if config_path is None or not config_path.exists():
            continue

        config = _read_json(config_path)
        server_name = _find_server_name(config, probe_keys)
        if server_name is None:
            continue

        server = config.setdefault("mcpServers", {}).setdefault(server_name, {})
        mode = _entry_mode(server)

        if mode == "docker-args":
            args: list[str] = server.setdefault("args", [])
            _update_docker_env(args, env_values)
            _write_json(config_path, config)
            docker_cmd = _format_docker_command(server.get("command", "docker"), args)
            print(f"\n✅ {host_name} config updated: {config_path}")
            print(f"   Server '{server_name}' — keys written: {', '.join(env_values)}")
            print(f"\n🐳 Docker command (what Claude will run):\n{docker_cmd}")
            if supports_restart:
                restart_claude = True

        elif mode == "env-block":
            server.setdefault("env", {}).update(env_values)
            _write_json(config_path, config)
            print(f"\n✅ {host_name} config updated: {config_path}")
            print(f"   Server '{server_name}' — keys written: {', '.join(env_values)}")
            if supports_restart:
                restart_claude = True

        elif mode == "sse-url":
            # Config only holds the URL — credentials go to the running server,
            # however it was started (Docker, IDE, java -jar, etc.).
            sse_url = server.get("url", "http://localhost:8081/sse")
            image = os.environ.get("DOCKER_IMAGE", "broker-mcp")
            _print_sse_instructions(env_values, image)
            print(f"\n   SSE endpoint: {sse_url}")

        updated_any = True

    if not updated_any:
        # No matching entry found anywhere — check if we should print SSE/IDE instructions.
        if os.environ.get("MCP_SERVER_PROTOCOL", "").upper() == "SSE":
            image = os.environ.get("DOCKER_IMAGE", "broker-mcp")
            _print_sse_instructions(env_values, image)
        else:
            _print_fallback_instructions(env_values)
        return

    if restart and restart_claude and not os.environ.get("MCP_NO_RESTART"):
        _restart_claude_desktop()


# ── Local fallback ─────────────────────────────────────────────────────────────

def _print_fallback_instructions(env_values: dict[str, str]) -> None:
    jar_path = str(Path(__file__).parent / "target" / "broker-mcp-0.0.1-SNAPSHOT.jar")
    sample_config = {
        "mcpServers": {
            "trading": {
                "command": "java",
                "args": ["-jar", jar_path],
                "env": env_values,
            }
        }
    }

    print("\n⚠️  No existing MCP server entry found in any host config.")
    print("\n   Add the following to your mcpServers config and restart your AI agent.")
    print("   Config file locations:")
    for host_name, resolve_path, _ in _HOST_RESOLVERS:
        path = resolve_path()
        if path:
            print(f"   • {host_name}: {path}")
    print(f"\n{json.dumps(sample_config, indent=2)}")
