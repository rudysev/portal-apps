#!/bin/bash
# macOS double-click entry point. Double-click in Finder to install Jarvis + the wake listener.
cd "$(dirname "$0")" || exit 1
chmod +x install.sh 2>/dev/null
./install.sh
echo
read -n 1 -s -r -p "Press any key to close this window…"
echo
