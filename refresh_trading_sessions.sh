#!/usr/bin/env bash
# Refresh trading sessions for Breeze (ICICI Direct) and/or Zerodha.
# Updates your AI agent's MCP config with fresh tokens and restarts Claude Desktop once.
#
# Usage:
#   ./refresh_trading_sessions.sh              # auto-detect configured brokers
#   ./refresh_trading_sessions.sh breeze       # Breeze (ICICI Direct) only
#   ./refresh_trading_sessions.sh zerodha      # Zerodha only
#   ./refresh_trading_sessions.sh both         # both, sequentially
#   ./refresh_trading_sessions.sh show-config  # print MCP configs for all four modes
#
# Override Python binary:
#   PYTHON_BIN=/path/to/python3 ./refresh_trading_sessions.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ -f "$SCRIPT_DIR/.env" ]]; then
    set -o allexport
    # shellcheck source=/dev/null
    source "$SCRIPT_DIR/.env"
    set +o allexport
fi

PYTHON_BIN="${PYTHON_BIN:-python3}"
MODE="${1:-auto}"

# Suppress per-script restarts — we do one restart at the end.
export MCP_NO_RESTART=1

has_breeze() {
    [[ -n "${BREEZE_API_KEY:-}" && -n "${BREEZE_SECRET:-}" ]]
}

has_zerodha() {
    [[ -n "${ZERODHA_API_KEY:-}" && -n "${ZERODHA_API_SECRET:-}" ]]
}

should_export_zerodha_tradebook() {
    [[ "${ZERODHA_TRADEBOOK_AUTO_EXPORT:-false}" == "true" ]]
}

run_breeze() {
    echo "=============================="
    echo "  Breeze (ICICI Direct) Login"
    echo "=============================="
    "$PYTHON_BIN" "$SCRIPT_DIR/scripts/automation.py"
}

run_zerodha() {
    echo "=============================="
    echo "  Zerodha Login"
    echo "=============================="
    "$PYTHON_BIN" "$SCRIPT_DIR/zerodha_login.py"
}

run_zerodha_tradebook() {
    echo ""
    echo "=============================="
    echo "  Zerodha Tradebook Export"
    echo "=============================="
    "$PYTHON_BIN" "$SCRIPT_DIR/zerodha_tradebook.py"
}

announce_zerodha_tradebook_import() {
    local import_root="${ZERODHA_TRADEBOOK_IMPORT_ROOT:-$HOME/.broker-mcp/imports}"
    if [[ -n "$import_root" ]]; then
        echo ""
        echo "📘 Zerodha tradebook startup import enabled:"
        echo "   $import_root"
        echo "   CSVs in this directory will be imported on the next server start."
    fi
}

corporate_actions_store_path() {
    echo "${BROKER_CORPORATE_ACTIONS_STORE_PATH:-$HOME/.broker-mcp/stock-corporate-actions.json}"
}

show_config() {
    # Source the shared session file if available so tokens are filled in.
    local session_file="$HOME/.broker-mcp/.env.session"
    if [[ -f "$session_file" ]]; then
        set -o allexport
        # shellcheck source=/dev/null
        source "$session_file"
        set +o allexport
    fi

    local jar_path="$SCRIPT_DIR/target/broker-mcp-0.0.1-SNAPSHOT.jar"
    local image="${DOCKER_IMAGE:-broker-mcp}"
    local breeze_session="${BREEZE_SESSION:-<run refresh to obtain>}"
    local zerodha_token="${ZERODHA_ACCESS_TOKEN:-<run refresh to obtain>}"
    local corporate_actions_store
    corporate_actions_store="$(corporate_actions_store_path)"

    # Build env flag blocks used in multiple modes.
    local env_block=""
    local env_flags=""
    local env_inline=""
    if [[ -n "${BREEZE_API_KEY:-}" ]]; then
        env_block="${env_block}      \"BREEZE_ENABLED\":       \"${BREEZE_ENABLED:-true}\",\n"
        env_block="${env_block}      \"BREEZE_API_KEY\":       \"${BREEZE_API_KEY}\",\n"
        env_block="${env_block}      \"BREEZE_SECRET\":        \"${BREEZE_SECRET:-}\",\n"
        env_block="${env_block}      \"BREEZE_SESSION\":       \"$breeze_session\",\n"
        env_flags="${env_flags}  -e BREEZE_ENABLED=${BREEZE_ENABLED:-true} \\\\\n  -e BREEZE_API_KEY=${BREEZE_API_KEY} \\\\\n  -e BREEZE_SECRET=${BREEZE_SECRET:-} \\\\\n  -e BREEZE_SESSION=$breeze_session \\\\\n"
        env_inline="${env_inline}BREEZE_ENABLED=${BREEZE_ENABLED:-true} \\\n  BREEZE_API_KEY=${BREEZE_API_KEY} \\\n  BREEZE_SESSION=$breeze_session \\\n"
    fi
    if [[ -n "${ZERODHA_API_KEY:-}" ]]; then
        env_block="${env_block}      \"ZERODHA_ENABLED\":      \"${ZERODHA_ENABLED:-true}\",\n"
        env_block="${env_block}      \"ZERODHA_API_KEY\":      \"${ZERODHA_API_KEY}\",\n"
        env_block="${env_block}      \"ZERODHA_USER_ID\":      \"${ZERODHA_USER_ID:-}\",\n"
        env_block="${env_block}      \"ZERODHA_ACCESS_TOKEN\": \"$zerodha_token\"\n"
        env_flags="${env_flags}  -e ZERODHA_ENABLED=${ZERODHA_ENABLED:-true} \\\\\n  -e ZERODHA_API_KEY=${ZERODHA_API_KEY} \\\\\n  -e ZERODHA_USER_ID=${ZERODHA_USER_ID:-} \\\\\n  -e ZERODHA_ACCESS_TOKEN=$zerodha_token \\\\\n"
        env_inline="${env_inline}ZERODHA_ENABLED=${ZERODHA_ENABLED:-true} \\\n  ZERODHA_API_KEY=${ZERODHA_API_KEY} \\\n  ZERODHA_USER_ID=${ZERODHA_USER_ID:-} \\\n  ZERODHA_ACCESS_TOKEN=$zerodha_token \\\n"
    fi

    echo ""
    echo "════════════════════════════════════════════════════════════"
    echo "  MCP Server Configurations"
    echo "════════════════════════════════════════════════════════════"

    echo ""
    echo "── Mode 1: stdio (JAR) ──────────────────────────────────────"
    echo "claude_desktop_config.json → mcpServers:"
    echo ""
    echo "  \"trading\": {"
    echo "    \"command\": \"java\","
    echo "    \"args\": [\"-jar\", \"$jar_path\"],"
    echo "    \"env\": {"
    echo -e "$env_block" | sed 's/^/      /'
    echo "    }"
    echo "  }"

    echo ""
    echo "── Mode 2: stdio (Docker) ───────────────────────────────────"
    echo "claude_desktop_config.json → mcpServers (requires built image: $image):"
    echo ""
    echo "  \"trading\": {"
    echo "    \"command\": \"docker\","
    echo "    \"args\": ["
    echo "      \"run\", \"-i\", \"--rm\","
    echo "      \"-v\", \"$HOME/.broker-mcp:/data\","
    echo "      \"-e\", \"SPRING_PROFILES_ACTIVE=\","
    echo "      \"-e\", \"BROKER_CORPORATE_ACTIONS_STORE_PATH=/data/stock-corporate-actions.json\","
    if [[ -n "${BREEZE_API_KEY:-}" ]]; then
        echo "      \"-e\", \"BREEZE_ENABLED=${BREEZE_ENABLED:-true}\","
        echo "      \"-e\", \"BREEZE_API_KEY=${BREEZE_API_KEY}\","
        echo "      \"-e\", \"BREEZE_SECRET=${BREEZE_SECRET:-}\","
        echo "      \"-e\", \"BREEZE_SESSION=$breeze_session\","
    fi
    if [[ -n "${ZERODHA_API_KEY:-}" ]]; then
        echo "      \"-e\", \"ZERODHA_ENABLED=${ZERODHA_ENABLED:-true}\","
        echo "      \"-e\", \"ZERODHA_API_KEY=${ZERODHA_API_KEY}\","
        echo "      \"-e\", \"ZERODHA_USER_ID=${ZERODHA_USER_ID:-}\","
        echo "      \"-e\", \"ZERODHA_ACCESS_TOKEN=$zerodha_token\","
    fi
    echo "      \"$image\""
    echo "    ]"
    echo "  }"
    echo ""
    echo "  Note: -e SPRING_PROFILES_ACTIVE= overrides the image default"
    echo "  so the container starts in stdio mode instead of HTTP (streamable)."

    echo ""
    echo "── Mode 3: HTTP Streamable (Docker, persistent) ─────────────"
    echo "claude_desktop_config.json → mcpServers (set once, never changes):"
    echo ""
    echo "  \"trading\": { \"url\": \"http://localhost:8081/mcp\" }"
    echo ""
    echo "Restart container after each token refresh:"
    echo ""
    echo "  docker stop $image 2>/dev/null; docker rm $image 2>/dev/null"
    echo "  docker run -d --name $image -p 8081:8081 \\"
    echo "  -v \"\$HOME/.broker-mcp:/data\" \\"
    echo "  -e BROKER_CORPORATE_ACTIONS_STORE_PATH=/data/stock-corporate-actions.json \\"
    echo -e "$env_flags" | sed 's/\\\\$/\\/'
    echo "  $image"

    echo ""
    echo "── Mode 4: HTTP Streamable (local JAR / IDE) ────────────────"
    echo "claude_desktop_config.json → mcpServers (set once, never changes):"
    echo ""
    echo "  \"trading\": { \"url\": \"http://localhost:8081/mcp\" }"
    echo ""
    echo "Start / restart the server after each token refresh:"
    echo ""
    echo "  SPRING_PROFILES_ACTIVE=http \\"
    echo -e "$env_inline" | sed 's/\\\\$/\\/'
    echo "  java -jar $jar_path"
    echo ""
    echo "  Or set SPRING_PROFILES_ACTIVE=http plus the above vars"
    echo "  in your IDE run configuration and restart the process."
    echo ""
    echo "Corporate-action store:"
    echo "  $corporate_actions_store"
    echo ""
    echo "════════════════════════════════════════════════════════════"
}

restart_claude() {
    echo ""
    echo "🔄 Restarting Claude Desktop..."
    if [[ "$(uname)" == "Darwin" ]]; then
        osascript -e 'tell application "Claude" to quit' 2>/dev/null || true
        sleep 2
        open -a Claude 2>/dev/null || true
        echo "   ✅ Claude Desktop restarted."
    else
        echo "   ℹ️  Restart Claude Desktop manually to pick up the new session tokens."
    fi
}

case "$MODE" in
    auto)
        ran_any=false
        if has_breeze;  then run_breeze;  ran_any=true; fi
        if has_zerodha; then
            run_zerodha
            if should_export_zerodha_tradebook; then run_zerodha_tradebook; fi
            announce_zerodha_tradebook_import
            ran_any=true
        fi
        if [[ "$ran_any" == false ]]; then
            echo "❌ No broker credentials found in .env"
            echo "   Copy .env.example to .env and fill in your credentials."
            exit 1
        fi
        ;;
    breeze)
        has_breeze || { echo "❌ Breeze credentials incomplete in .env"; exit 1; }
        run_breeze
        ;;
    zerodha)
        has_zerodha || { echo "❌ Zerodha credentials incomplete in .env"; exit 1; }
        run_zerodha
        if should_export_zerodha_tradebook; then run_zerodha_tradebook; fi
        announce_zerodha_tradebook_import
        ;;
    both)
        has_breeze  || { echo "❌ Breeze credentials incomplete in .env"; exit 1; }
        has_zerodha || { echo "❌ Zerodha credentials incomplete in .env"; exit 1; }
        run_breeze
        run_zerodha
        if should_export_zerodha_tradebook; then run_zerodha_tradebook; fi
        announce_zerodha_tradebook_import
        ;;
    show-config|config)
        show_config
        exit 0
        ;;
    -h|--help|help)
        echo "Usage: $0 [auto|breeze|zerodha|both|show-config]"
        echo ""
        echo "  auto         Auto-detect configured brokers (default)"
        echo "  breeze       Refresh ICICI Direct Breeze session only"
        echo "  zerodha      Refresh Zerodha session only"
        echo "  both         Refresh both sequentially"
        echo "  show-config  Print MCP client config for all four deployment modes"
        echo ""
        echo "Optional Zerodha tradebook export:"
        echo "  ZERODHA_TRADEBOOK_AUTO_EXPORT=true"
        echo ""
        echo "Override Python binary: PYTHON_BIN=/path/to/python3 $0"
        exit 0
        ;;
    *)
        echo "Usage: $0 [auto|breeze|zerodha|both|show-config]"
        exit 1
        ;;
esac

restart_claude

echo ""
echo "✅ Session refresh complete."
