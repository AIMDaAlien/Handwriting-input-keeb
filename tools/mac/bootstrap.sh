#!/usr/bin/env bash
# One-shot, idempotent dev setup for the M4 Air. Re-run any time; it only adds.
#
# What it does:
#   1. Homebrew + everything in Brewfile
#   2. Android SDK at the Android Studio default path (~/Library/Android/sdk) so the
#      IDE and the CLI share ONE SDK. Installs exactly the packages FUTO's CI uses.
#   3. ANDROID_HOME / JAVA_HOME / PATH in ~/.zprofile
#   4. Clones this repo recursively (incl. FUTO + its submodules) into ~/dev unless
#      you run it from inside an existing clone.
#   5. Runs the recognizer-core tests as a smoke test.
#
# Usage:  bash tools/mac/bootstrap.sh            (from a clone)
#    or:  curl -fsSL <raw url of this file> | bash
set -euo pipefail

SDK="$HOME/Library/Android/sdk"
PLATFORM=35
BUILD_TOOLS=35.0.0
NDK=28.2.13676358
CMAKE=3.22.1
REPO_URL="https://github.com/AIMDaAlien/Handwriting-input-keeb.git"
REPO_BRANCH="claude/futo-handwritten-input-g1igu1"

step() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }   # lavender, naturally

[[ "$(uname -s)" == "Darwin" ]] || { echo "This script is for macOS."; exit 1; }
[[ "$(uname -m)" == "arm64" ]]  || echo "warning: not Apple Silicon; casks still work but check the JDK arch"
# Fail before any download: a sudo-run installer can leave ~/.zprofile owned by root.
if [[ -e "$HOME/.zprofile" && ! -w "$HOME/.zprofile" ]]; then
  echo "~/.zprofile is owned by $(stat -f %Su "$HOME/.zprofile") and not writable. Fix, then re-run:"
  echo "  sudo chown $(id -un) ~/.zprofile"
  exit 1
fi

step "Xcode command line tools (git needs them)"
xcode-select -p >/dev/null 2>&1 || { xcode-select --install; echo "Finish the CLT dialog, then re-run."; exit 1; }

step "Homebrew"
if ! command -v brew >/dev/null; then
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi
eval "$(/opt/homebrew/bin/brew shellenv)"

step "Brew bundle"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || true)"
if [[ -n "$HERE" && -f "$HERE/Brewfile" ]]; then
  brew bundle --file="$HERE/Brewfile" --no-upgrade
else
  TMPB="$(mktemp)"
  curl -fsSL "https://raw.githubusercontent.com/AIMDaAlien/Handwriting-input-keeb/$REPO_BRANCH/tools/mac/Brewfile" -o "$TMPB"
  brew bundle --file="$TMPB" --no-upgrade
fi
git lfs install --skip-repo >/dev/null
# Earlier Brewfiles installed a second adb; two adb versions keep killing each other's server.
if brew list --cask android-platform-tools >/dev/null 2>&1; then
  brew uninstall --cask android-platform-tools
fi

step "Android SDK packages into $SDK (shared with Android Studio)"
mkdir -p "$SDK"
SDKMANAGER="$(brew --prefix)/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager"
[[ -x "$SDKMANAGER" ]] || SDKMANAGER="$(ls "$(brew --prefix)"/share/android-commandlinetools/cmdline-tools/*/bin/sdkmanager | head -1)"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" --install \
  "cmdline-tools;latest" \
  "platform-tools" \
  "platforms;android-$PLATFORM" \
  "build-tools;$BUILD_TOOLS" \
  "ndk;$NDK" \
  "cmake;$CMAKE"

step "Shell environment (~/.zprofile)"
ZP="$HOME/.zprofile"; touch "$ZP"
add_line() { grep -qF -- "$1" "$ZP" || echo "$1" >> "$ZP"; }
add_line '# --- android dev (tools/mac/bootstrap.sh) ---'
add_line 'eval "$(/opt/homebrew/bin/brew shellenv)"'
add_line 'export JAVA_HOME="$(/usr/libexec/java_home -v 21)"'
add_line "export ANDROID_HOME=\"$SDK\""
add_line 'export ANDROID_SDK_ROOT="$ANDROID_HOME"'
add_line 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"'
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

step "Repository"
if git -C "$HERE/../.." rev-parse --show-toplevel >/dev/null 2>&1 && [[ -f "$HERE/../../settings.gradle.kts" ]]; then
  ROOT="$(git -C "$HERE/../.." rev-parse --show-toplevel)"
else
  mkdir -p "$HOME/dev"; ROOT="$HOME/dev/Handwriting-input-keeb"
  [[ -d "$ROOT/.git" ]] || git clone --branch "$REPO_BRANCH" "$REPO_URL" "$ROOT"
fi
cd "$ROOT"
# Full history, not --depth 1: FUTO's build.gradle derives versionCode from git history + tags.
git submodule update --init --recursive || echo "warning: a submodule failed (FUTO GitLab or Hugging Face); the keyboard still builds, swipe may degrade"
git -C upstream fetch --tags --force origin || true
git -C upstream/java/assets/futo-swipe lfs pull 2>/dev/null || true

step "Python venv for upstream's download-bundles.py (requests)"
[[ -x "$ROOT/.venv/bin/python3" ]] || "$(brew --prefix python@3.12)/bin/python3.12" -m venv "$ROOT/.venv"
"$ROOT/.venv/bin/python3" -c 'import requests' 2>/dev/null || "$ROOT/.venv/bin/pip" install --quiet requests

step "Smoke test: recognizer-core (no Android SDK needed)"
./gradlew --quiet :recognizer-core:test && echo "recognizer-core: tests green"

step "Smoke test: adb (must be the SDK one)"
[[ "$(command -v adb)" == "$SDK/platform-tools/adb" ]] || echo "warning: adb resolves to $(command -v adb), not the SDK copy"
adb version | head -1
adb devices

cat <<MSG

Done. Open a NEW terminal (or: source ~/.zprofile), then:

  cd $ROOT
  scripts/phase0-device-check.sh          # tablet plugged in, USB debugging on
  cd upstream && ./gradlew assembleUnstableDebug   # ~10-25 min first time, or let CI do it

Only if you run upstream's updateBundleResources task: source .venv/bin/activate first
(it needs requests; the normal build only needs stdlib python3).

Android Studio: on first launch pick "Custom", set SDK location to
  $SDK
so it reuses this SDK instead of downloading a second one. Open $ROOT/upstream as the project.
MSG
