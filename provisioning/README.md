# Install both apps on your Portal

No building, no command line, no developer tools. You just need the Portal, a USB‑C cable, and a
computer. This installs **both** Portal apps at once:

- **Jarvis** (`portal-assistant`) — the voice assistant.
- **the "hey jarvis" wake listener** (`portal-wake`) — the always-on hotword that triggers Jarvis.

## Steps

1. **On the Portal:** open **Settings → Debug** and turn on **ADB Enabled**.
2. **Connect** the Portal to your computer with a **USB‑C cable**.
3. **[Download this repository as a ZIP](https://github.com/rudysev/portal-apps/archive/refs/heads/main.zip)**
   and unzip it.
4. Open the `provisioning` folder and **double-click** the installer for your computer:
   - **macOS:** `Install-PortalApps.command`
   - **Windows:** `Install-PortalApps.bat`
5. When the Portal screen shows **"Allow USB debugging?"**, tap **Allow** (tick "Always allow from this
   computer").
6. When the installer asks for a **Google Gemini API key**, follow the on-screen steps to create a free
   one and paste it in (or press Enter to skip and add it later in Jarvis → Settings → API key).
7. Wait for **"Done."** — then say **"hey jarvis"** near the Portal.

The installer does everything else automatically: it downloads Android's `adb` if you don't have it,
downloads both apps (about 240 MB total — portal-wake bundles the on‑device speech model), installs
them, grants the microphone and other permissions, and starts them. Jarvis has an app icon; the wake
listener has **no icon** and runs in the background, starting again on every reboot.

> **You do not need git.** The ZIP doesn't include the two apps' files, so the installer downloads them
> from GitHub the first time it runs. (Developers who cloned the repo with
> `git clone --recursive` already have everything locally — see the root `README.md`.)

## To remove it

Double-click **`Uninstall-PortalApps`** (`.command` on macOS, `.bat` on Windows). This removes both apps
and **restores Meta's built-in "Hey Alexa"** to its original state (the wake app disables it during
install so "hey jarvis" can use the microphone; uninstalling re-enables it).

## To add or change your Gemini API key later

Double-click **`Set-Jarvis-Key`**. To check what's installed, double-click **`Status-PortalApps`**.

## Notes & troubleshooting

- **macOS "unidentified developer":** if double-clicking is blocked, right-click
  `Install-PortalApps.command` → **Open** → **Open**.
- **Windows "blocked files":** Windows marks files downloaded from the internet as blocked. If a script
  won't run, right-click it → **Properties** → tick **Unblock** → **OK**, then try again. (If Windows
  SmartScreen appears, choose **More info → Run anyway**.)
- **"More than one device is connected":** unplug other Android devices and re-run.
- **No internet on first run:** the installer needs a connection the first time (to download `adb`, the
  app installers, and the APKs). After that the downloaded files are cached next to the scripts.
- **Command line:** the same actions are available as `./install.sh`, `./install.sh --uninstall`,
  `./install.sh --key`, and `./install.sh --status` (use the `.bat`/`install.ps1` equivalents on
  Windows).

## Requirements

- macOS, Linux, or Windows — **no git or developer tools needed** for the ZIP + double-click path.
- A USB-C cable and a Portal with ADB enabled.
- An internet connection on first run, and a free Google Gemini API key (the installer walks you
  through creating one).
