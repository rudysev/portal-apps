# portal-apps one-click installer for the Meta Portal (Windows).
#
# Installs BOTH Jarvis (portal-assistant) and the "hey jarvis" wake listener (portal-wake) in one go,
# by delegating to each app's own installer — so you always get the current release of each. Finds (or
# downloads) Android's adb once, waits for a connected Portal, then runs the two installers in order
# (Jarvis first — it asks for your free Gemini key — then wake). No Android SDK, no build tools, no
# Node — just this script and a USB-C cable.
#
# Usage:
#   .\install.ps1                  install both apps (downloads the latest releases)
#   .\install.ps1 -Uninstall       remove both apps (and restore Meta's "Hey Alexa" detector)
#   .\install.ps1 -Key [-KeyValue K]  (re)enter the Gemini API key on an already-installed Jarvis
#   .\install.ps1 -Status          show whether each app is installed (and whether the key is set)
#
# NOTE: this is the Windows mirror of install.sh. It delegates to each app's install.ps1.
param([switch]$Uninstall, [switch]$Status, [switch]$Key, [string]$KeyValue)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir
$Root = Split-Path -Parent $ScriptDir   # the portal-apps superproject root

$AssistantDir = Join-Path $Root "portal-assistant\provisioning"
$WakeDir      = Join-Path $Root "portal-wake\provisioning"
$AssistantPs1 = Join-Path $AssistantDir "install.ps1"
$WakePs1      = Join-Path $WakeDir "install.ps1"

# Source repos + branch used to bootstrap the per-app installers from a plain (non-git) download —
# e.g. a ZIP, where the submodule folders arrive empty. Only the small provisioning scripts are
# fetched; each app still downloads its own APK from its GitHub release.
$AssistantRepo = "rudysev/portal-assistant"
$WakeRepo      = "rudysev/portal-wake"
$BootstrapRef  = "main"

# Stock Windows PowerShell 5.1 defaults to old TLS, which breaks HTTPS downloads. Force TLS 1.2.
try {
  [Net.ServicePointManager]::SecurityProtocol =
      [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {}
$PSDefaultParameterValues['Invoke-WebRequest:UseBasicParsing'] = $true

function Step($m){ Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m){ Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "  [!] $m" -ForegroundColor Yellow }
function Die($m){ Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ----- ensure the per-app installers are present -----------------------------
# Fetch one file and validate it: non-empty AND contains an expected marker. The marker guards against
# a 200-but-wrong body (captive-portal HTML, an empty/truncated transfer) — Invoke-WebRequest throws
# only on HTTP >=400 / a dropped connection, not on a "successful" wrong payload.
function Fetch-Verify($url, $dest, $marker) {
  Invoke-WebRequest -Uri $url -OutFile $dest
  if (-not (Test-Path $dest)) { throw "download produced no file: $url" }
  if ((Get-Item $dest).Length -eq 0) { throw "empty download: $url" }
  if (-not (Select-String -Path $dest -Pattern $marker -Quiet)) { throw "unexpected content (no '$marker'): $url" }
}

# Download just the small provisioning scripts each child installer needs (it fetches its own APK from
# its release), so a ZIP + double-click works with no git at all. All-or-nothing: stage every file to a
# temp dir and validate it; only move them into place once ALL succeed, so a partial/failed download
# never leaves a corrupt tree that a later run mistakes for good.
function Bootstrap-FromGitHub {
  Step "Downloading the app installers from GitHub (no git checkout detected)"
  $a = "https://raw.githubusercontent.com/$AssistantRepo/$BootstrapRef/provisioning"
  $w = "https://raw.githubusercontent.com/$WakeRepo/$BootstrapRef/provisioning"
  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("portal-boot-" + [System.IO.Path]::GetRandomFileName())
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  try {
    Fetch-Verify "$a/install.ps1" (Join-Path $tmp "a_install.ps1") "Resolve-Adb"
    Fetch-Verify "$a/config.env"  (Join-Path $tmp "a_config.env")  "RELEASE_REPO"
    Fetch-Verify "$w/install.ps1" (Join-Path $tmp "w_install.ps1") "Resolve-Adb"
    Fetch-Verify "$w/config.env"  (Join-Path $tmp "w_config.env")  "RELEASE_REPO"
    New-Item -ItemType Directory -Force -Path $AssistantDir, $WakeDir | Out-Null
    Move-Item -Force (Join-Path $tmp "a_install.ps1") $AssistantPs1
    Move-Item -Force (Join-Path $tmp "a_config.env")  (Join-Path $AssistantDir "config.env")
    Move-Item -Force (Join-Path $tmp "w_install.ps1") $WakePs1
    Move-Item -Force (Join-Path $tmp "w_config.env")  (Join-Path $WakeDir "config.env")
    Ok "App installers downloaded"
  } finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  }
}

function Ensure-Submodules {
  if ((Test-Path $AssistantPs1) -and (Test-Path $WakePs1)) { return }
  if (Test-Path (Join-Path $Root ".git")) {
    # A git checkout: the apps are submodules — pull them. Do NOT fall back to the network bootstrap
    # here: that would write unpinned main-branch content into the tracked submodule trees.
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
      Die "This is a git checkout but git isn't installed. Install git, then run: git submodule update --init --recursive (or re-clone with --recursive)."
    }
    Step "Fetching the app submodules (portal-assistant + portal-wake)"
    & git -C $Root submodule update --init --recursive
    if ($LASTEXITCODE -ne 0) { Die "git submodule update failed. Fix your network/credentials and re-run, or re-clone with: git clone --recursive https://github.com/rudysev/portal-apps.git" }
  } else {
    # Plain download (ZIP), no git: download the installers directly.
    try { Bootstrap-FromGitHub }
    catch { Die "Couldn't download the app installers. Check your internet connection and re-run, or get the full repo with: git clone --recursive https://github.com/rudysev/portal-apps.git" }
  }
  if (-not (Test-Path $AssistantPs1)) { Die "portal-assistant installer missing after download." }
  if (-not (Test-Path $WakePs1))      { Die "portal-wake installer missing after download." }
}

# ----- resolve adb (bundled -> PATH -> download), shared with the children ----
function Resolve-Adb {
  $bundled = Join-Path $ScriptDir "platform-tools\adb.exe"
  if (Test-Path $bundled) { return $bundled }
  $onPath = (Get-Command adb -ErrorAction SilentlyContinue)
  if ($onPath) { return $onPath.Source }
  Step "Android platform-tools (adb) not found - downloading the official package from Google"
  $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
  $zip = Join-Path $ScriptDir "platform-tools.zip"
  Invoke-WebRequest -Uri $url -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $ScriptDir -Force
  Remove-Item $zip
  if (-not (Test-Path $bundled)) { Die "adb missing after download." }
  Ok "platform-tools installed locally"
  return $bundled
}

# Put the resolved adb first on PATH so the delegated child scripts reuse it.
function Share-Adb($adb) { $env:PATH = (Split-Path -Parent $adb) + ";" + $env:PATH }

function Wait-Device($adb) {
  Step "Looking for your Portal"
  & $adb start-server | Out-Null
  $plug=$false; $auth=$false
  while ($true) {
    $raw = @(& $adb devices | Select-Object -Skip 1)
    $devs = @($raw | Where-Object { $_ -match "^\S+\s+device\b" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devs.Count -gt 1 -and -not $env:ANDROID_SERIAL) { Die "More than one device is connected. Unplug the others and re-run." }
    if ($devs.Count -eq 1) { $env:ANDROID_SERIAL = $devs[0]; $state = "device" }
    else {
      $line = ($raw | Where-Object { $_.Trim() } | Select-Object -First 1)
      $state = if ($line) { ($line -split "\s+")[1] } else { "" }
    }
    switch ($state) {
      "device" { $model = "$(& $adb shell getprop ro.product.model)".Trim(); Ok "Connected: $model"; return }
      "unauthorized" { if (-not $auth) { Warn "On the Portal screen, tap Allow (check 'Always allow from this computer')."; $auth=$true } }
      default { if (-not $plug) { Warn "Plug the Portal into this PC via USB-C. On the Portal: Settings > Debug > ADB Enabled."; $plug=$true } }
    }
    Start-Sleep -Seconds 2
  }
}

# Read PKG from a submodule's config.env, with a fallback default.
function Cfg-Pkg($file, $default) {
  if (Test-Path $file) {
    $line = (Get-Content $file | Where-Object { $_ -match '^PKG=' } | Select-Object -First 1)
    if ($line) {
      # Tolerate a quoted value and an inline comment (a package id has neither).
      $v = (($line -split "=", 2)[1] -replace '#.*$', '').Trim().Trim('"').Trim("'")
      if ($v) { return $v }
    }
  }
  return $default
}

# ----- top-level actions -----------------------------------------------------
if ($Status) {
  Ensure-Submodules
  $adb = Resolve-Adb; Share-Adb $adb; Wait-Device $adb
  Write-Host "`nJarvis (portal-assistant)" -ForegroundColor White
  & powershell -NoProfile -ExecutionPolicy Bypass -File $AssistantPs1 -Status
  Write-Host "`nWake listener (portal-wake)" -ForegroundColor White
  & powershell -NoProfile -ExecutionPolicy Bypass -File $WakePs1 -Status
  exit 0
}

if ($Uninstall) {
  Write-Host "portal-apps uninstaller" -ForegroundColor White
  Write-Host "Removes both apps and restores Meta's 'Hey Alexa' to its original state.`n" -ForegroundColor DarkGray
  # Self-contained on purpose: uninstall must work with no network and no child scripts present. Remove
  # the packages directly and restore "Hey Alexa" inline rather than delegating to the per-app installers.
  $adb = Resolve-Adb; Wait-Device $adb
  foreach ($pkg in @("com.portal.assistant", "com.portal.wake")) {
    Step "Removing $pkg"
    & $adb shell am force-stop $pkg | Out-Null
    $out = (& $adb uninstall $pkg 2>&1) -join " "
    if ($out -match "Success") { Ok "Uninstalled $pkg" } else { Warn "$pkg was not installed." }
  }
  # Re-enable Meta's "Hey Alexa" detector if portal-wake disabled it. Detect the disabled state with -d
  # (a disable-user package can be hidden from the default `pm list packages` on some Android builds).
  if ((& $adb shell pm list packages -d com.millennium) -match "package:com.millennium") {
    Step "Re-enabling Meta's 'Hey Alexa' detector"
    & $adb shell pm enable com.millennium | Out-Null; Ok "Re-enabled com.millennium"
  }
  Write-Host "`n[ok] Done. Both apps removed; Meta's 'Hey Alexa' restored to its original state." -ForegroundColor Green
  exit 0
}

if ($Key) {
  Ensure-Submodules
  $adb = Resolve-Adb; Share-Adb $adb
  if ($KeyValue) { & powershell -NoProfile -ExecutionPolicy Bypass -File $AssistantPs1 -Key -KeyValue $KeyValue }
  else           { & powershell -NoProfile -ExecutionPolicy Bypass -File $AssistantPs1 -Key }
  exit 0
}

# ----- install (default) -----------------------------------------------------
Write-Host "portal-apps installer" -ForegroundColor White
Write-Host "Installs Jarvis (the assistant) + the 'hey jarvis' wake listener on your Portal.`n" -ForegroundColor DarkGray
Ensure-Submodules
$adb = Resolve-Adb; Share-Adb $adb; Wait-Device $adb
$assistPkg = Cfg-Pkg (Join-Path $AssistantDir "config.env") "com.portal.assistant"
$wakePkg   = Cfg-Pkg (Join-Path $WakeDir "config.env") "com.portal.wake"

# The child install.ps1 swallows adb errors (e.g. `A install ... | Out-Null`) and returns 0 even when
# the install fails, so $LASTEXITCODE alone can report false success — verify the package is actually
# present afterwards. (The .sh children die on install failure, so install.sh needs no such check.)
Step "Installing Jarvis (portal-assistant)`n"
& powershell -NoProfile -ExecutionPolicy Bypass -File $AssistantPs1
if ($LASTEXITCODE -ne 0) { Die "Jarvis install failed." }
if (-not ((& $adb shell pm path $assistPkg) -match "package:")) { Die "Jarvis didn't install (package $assistPkg not found on the device)." }

Write-Host ""
Step "Installing the wake listener (portal-wake)`n"
& powershell -NoProfile -ExecutionPolicy Bypass -File $WakePs1
if ($LASTEXITCODE -ne 0) { Die "portal-wake install failed." }
if (-not ((& $adb shell pm path $wakePkg) -match "package:")) { Die "portal-wake didn't install (package $wakePkg not found on the device)." }

Write-Host "`n[ok] Done. Both apps are installed - say 'hey jarvis' near the Portal." -ForegroundColor Green
Write-Host "To remove them: re-run with -Uninstall (or double-click Uninstall-PortalApps)." -ForegroundColor DarkGray
