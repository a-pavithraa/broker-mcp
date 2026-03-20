#!/usr/bin/env bash
set -euo pipefail

JAR_PATH="target/broker-mcp-0.0.1-SNAPSHOT.jar"
SESSION_FILE="$HOME/.broker-mcp/.env.session"
PROFILE="${1:-}"

if [[ -f "$SESSION_FILE" ]]; then
    set -o allexport
    source "$SESSION_FILE"
    set +o allexport
    echo "Loaded session from: $SESSION_FILE"
else
    echo "⚠️  Session file not found: $SESSION_FILE"
    echo "   Run ./refresh_trading_sessions.sh first."
fi

if [[ -n "$PROFILE" ]]; then
    export SPRING_PROFILES_ACTIVE="$PROFILE"
fi

echo "Launching: $JAR_PATH"
[[ -n "${SPRING_PROFILES_ACTIVE:-}" ]] && echo "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE"

exec java ${SPRING_PROFILES_ACTIVE:+"-Dspring.profiles.active=$SPRING_PROFILES_ACTIVE"} -jar "$JAR_PATH"
