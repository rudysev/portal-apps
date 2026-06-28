#!/bin/bash
# macOS double-click entry point. Double-click to check whether both apps are installed.
cd "$(dirname "$0")" || exit 1
chmod +x install.sh 2>/dev/null
./install.sh --status
echo
read -n 1 -s -r -p "Press any key to close this window…"
echo
