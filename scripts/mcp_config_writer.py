"""
MCP session file writer
========================
Writes fresh session tokens to ``~/.broker-mcp/.env.session`` and prints
a reminder to configure the MCP host per the README.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path


# ── Session file ───────────────────────────────────────────────────────────────

_SESSION_FILE = Path.home() / ".broker-mcp" / ".env.session"
_SYNC_PROBE_KEYS = [
    "BREEZE_API_KEY",
    "BREEZE_SESSION",
    "ZERODHA_API_KEY",
    "ZERODHA_ACCESS_TOKEN",
]


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


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


# ── Public API ─────────────────────────────────────────────────────────────────

def update_mcp_env(
    probe_keys: list[str],
    env_values: dict[str, str],
    restart: bool = True,
) -> None:
    """
    Print the session keys that were saved and point the user to the README
    for MCP config and server restart instructions.
    """
    print(f"\n💾 Session keys written: {', '.join(env_values)}")
    print(f"   Session file: {_SESSION_FILE}")
    print("\n📖 See README.md → 'Deployment Modes' for how to configure your")
    print("   MCP host and restart the server with the new credentials.")


def update_mcp_from_session_file(
    session_file: Path | None = None,
    restart: bool = True,
) -> None:
    target_file = session_file or _SESSION_FILE
    env_values = _read_env_file(target_file)
    if not env_values:
        print(f"\n⚠️  No session values found in: {target_file}")
        return

    probe_keys = [key for key in _SYNC_PROBE_KEYS if key in env_values]
    if not probe_keys:
        print(f"\n⚠️  No broker session keys found in: {target_file}")
        return

    update_mcp_env(
        probe_keys=probe_keys,
        env_values=env_values,
        restart=restart,
    )