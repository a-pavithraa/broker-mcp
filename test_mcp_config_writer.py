import io
import json
import os
import shutil
import sys
import unittest
from pathlib import Path
from uuid import uuid4
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent / "scripts"))

import mcp_config_writer


class McpConfigWriterTests(unittest.TestCase):

    def make_temp_dir(self) -> Path:
        temp_dir = Path.cwd() / f".tmp-test-{uuid4().hex}"
        temp_dir.mkdir()
        self.addCleanup(lambda: shutil.rmtree(temp_dir, ignore_errors=True))
        return temp_dir

    def test_update_mcp_env_skips_config_changes_when_deferred(self):
        temp_dir = self.make_temp_dir()
        config_path = temp_dir / "config.json"
        original = {
            "mcpServers": {
                "trading": {
                    "command": "java",
                    "args": ["-jar", "broker-mcp.jar"],
                    "env": {
                        "BREEZE_API_KEY": "old-key",
                        "BREEZE_SESSION": "old-session",
                    },
                }
            }
        }
        config_path.write_text(json.dumps(original), encoding="utf-8")

        output = io.StringIO()
        with patch.object(
            mcp_config_writer,
            "_HOST_RESOLVERS",
            [("Claude Desktop", lambda: config_path, True)],
        ), patch.dict(os.environ, {"MCP_NO_CONFIG_UPDATE": "1"}, clear=False), patch(
            "sys.stdout",
            output,
        ):
            mcp_config_writer.update_mcp_env(
                probe_keys=["BREEZE_API_KEY", "BREEZE_SESSION"],
                env_values={
                    "BREEZE_API_KEY": "new-key",
                    "BREEZE_SESSION": "new-session",
                },
            )

        self.assertEqual(original, json.loads(config_path.read_text(encoding="utf-8")))
        self.assertEqual("", output.getvalue())

    def test_update_mcp_from_session_file_merges_both_brokers(self):
        temp_dir = self.make_temp_dir()
        session_file = temp_dir / ".env.session"
        session_file.write_text(
            "\n".join(
                [
                    "BREEZE_ENABLED=true",
                    "BREEZE_API_KEY=icici-key",
                    "BREEZE_SECRET=icici-secret",
                    "BREEZE_SESSION=icici-session",
                    "ZERODHA_ENABLED=true",
                    "ZERODHA_API_KEY=zerodha-key",
                    "ZERODHA_USER_ID=AB1234",
                    "ZERODHA_ACCESS_TOKEN=zerodha-token",
                ]
            )
            + "\n",
            encoding="utf-8",
        )

        calls = []

        def capture_call(*, probe_keys, env_values, restart):
            calls.append(
                {
                    "probe_keys": probe_keys,
                    "env_values": env_values,
                    "restart": restart,
                }
            )

        self.assertTrue(
            hasattr(mcp_config_writer, "update_mcp_from_session_file"),
            "update_mcp_from_session_file should exist",
        )

        with patch.object(mcp_config_writer, "update_mcp_env", side_effect=capture_call):
            mcp_config_writer.update_mcp_from_session_file(session_file=session_file)

        self.assertEqual(1, len(calls))
        self.assertEqual(
            {
                "BREEZE_ENABLED": "true",
                "BREEZE_API_KEY": "icici-key",
                "BREEZE_SECRET": "icici-secret",
                "BREEZE_SESSION": "icici-session",
                "ZERODHA_ENABLED": "true",
                "ZERODHA_API_KEY": "zerodha-key",
                "ZERODHA_USER_ID": "AB1234",
                "ZERODHA_ACCESS_TOKEN": "zerodha-token",
            },
            calls[0]["env_values"],
        )
        self.assertEqual(
            [
                "BREEZE_API_KEY",
                "BREEZE_SESSION",
                "ZERODHA_API_KEY",
                "ZERODHA_ACCESS_TOKEN",
            ],
            calls[0]["probe_keys"],
        )
        self.assertTrue(calls[0]["restart"])


if __name__ == "__main__":
    unittest.main()
