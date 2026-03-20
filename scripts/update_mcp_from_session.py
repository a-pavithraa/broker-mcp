#!/usr/bin/env python3

from mcp_config_writer import update_mcp_from_session_file


if __name__ == "__main__":
    update_mcp_from_session_file(restart=False)
