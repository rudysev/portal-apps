# portal-apps

The workspace for the Portal voice stack. It pins three repos as git submodules:

- **[`portal-assistant`](https://github.com/rudysev/portal-assistant/tree/main)** — **Jarvis**, the
  conversational voice assistant (`com.portal.assistant`).
- **[`portal-wake`](https://github.com/rudysev/portal-wake)** — the always-on **"hey jarvis"**
  wake-word listener that triggers Jarvis (`com.portal.wake`).
- **[`portal-commons`](portal-commons/)** — shared Kotlin/JVM library used by both apps.

<p align="center">
  <a href="https://buymeacoffee.com/linuxbarista"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="200"></a>
</p>

## Install everything on your Portal

Installs **both** apps in one go — **no git or developer tools required**:

1. On the Portal: **Settings → Debug → ADB Enabled**, then connect it via **USB-C**.
2. **[Download this repo as a ZIP](https://github.com/rudysev/portal-apps/archive/refs/heads/main.zip)**,
   unzip it, open the `provisioning` folder, and double-click **`Install-PortalApps.command`** (macOS)
   or **`Install-PortalApps.bat`** (Windows).
3. Tap **Allow** on the Portal's "Allow USB debugging?" prompt, then paste a free Gemini key when asked.

The ZIP doesn't include the two apps' files, so the installer downloads them from GitHub on first run.
See **[`provisioning/README.md`](provisioning/README.md)** for the full walkthrough, OS-warning fixes,
uninstall, and the Gemini-key shortcut. To install just one app, use that app's own repo
([portal-assistant](https://github.com/rudysev/portal-assistant/tree/main) /
[portal-wake](https://github.com/rudysev/portal-wake)).

Developers can instead `git clone --recursive https://github.com/rudysev/portal-apps.git` and run
`provisioning/install.sh` against the local checkout.

## Working on the code

```bash
git submodule update --init --recursive
```

Each submodule builds independently (see its own `CLAUDE.md` / `README.md`); `portal-commons` is wired
into the two apps via a Gradle composite build from the sibling checkout.

## Disclaimer

These are independent community projects — **not affiliated with, endorsed by, or sponsored by Meta or
Google**. "Meta Portal" and "Portal" are trademarks of Meta Platforms, Inc., and "Gemini" is a
trademark of Google LLC, used here only to identify compatible hardware and the AI service Jarvis talks
to. These are sideloaded apps for discontinued devices and are **use-at-your-own-risk** (may void
warranty; no guarantees). Jarvis is **not** on-device only: to answer you it streams your microphone
audio to **Google's Gemini Live API** under **your own** API key, subject to Google's terms and privacy
policy. See each app's `DISCLAIMER.md` for the full text and privacy notes.
