#!/bin/bash
# macOS double-click entry point. Double-click to remove both apps (leaves Meta's "Hey Alexa" as-is).
cd "$(dirname "$0")" || exit 1
chmod +x install.sh 2>/dev/null
./install.sh --uninstall
echo
read -n 1 -s -r -p "Press any key to close this window…"
echo
