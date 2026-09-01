#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SDK="/opt/homebrew/share/android-commandlinetools"
if [[ -n "${ANDROID_HOME:-}" ]]; then
  SDK_DIR="$ANDROID_HOME"
elif [[ -d "$DEFAULT_SDK" ]]; then
  SDK_DIR="$DEFAULT_SDK"
else
  SDK_DIR="$ROOT_DIR/.android-sdk"
fi
ADB="$SDK_DIR/platform-tools/adb"
ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" ]]; then
  CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac_arm64-15859902_latest.zip"
else
  CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac_x86_64-15859902_latest.zip"
fi
CMDLINE_ZIP="/tmp/android-commandlinetools-mac-$ARCH.zip"
PACKAGE_NAME="com.example.touchevidence"
MAIN_ACTIVITY="$PACKAGE_NAME/.MainActivity"

usage() {
  cat <<USAGE
Usage:
  scripts/install-on-phone.sh --pair PAIR_HOST:PAIR_PORT PAIR_CODE --connect DEVICE_HOST:ADB_PORT
  scripts/install-on-phone.sh --connect DEVICE_HOST:ADB_PORT
  scripts/install-on-phone.sh

On the phone:
  Settings > Developer options > Wireless debugging
  Use "Pair device with pairing code" for --pair.
  Use the main Wireless debugging IP address and port for --connect.
USAGE
}

PAIR_TARGET=""
PAIR_CODE=""
CONNECT_TARGET=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pair)
      PAIR_TARGET="${2:-}"
      PAIR_CODE="${3:-}"
      shift 3
      ;;
    --connect)
      CONNECT_TARGET="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

ensure_sdk() {
  mkdir -p "$SDK_DIR"

  if [[ ! -x "$ADB" ]]; then
    echo "Installing Android platform-tools into $SDK_DIR"
    curl -L "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" -o /tmp/platform-tools-latest-darwin.zip
    unzip -qo /tmp/platform-tools-latest-darwin.zip -d "$SDK_DIR"
  fi

  if [[ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
    echo "Installing Android command-line tools into $SDK_DIR"
    curl -L "$CMDLINE_TOOLS_URL" -o "$CMDLINE_ZIP"
    rm -rf "$SDK_DIR/cmdline-tools"
    mkdir -p "$SDK_DIR/cmdline-tools/latest"
    unzip -qo "$CMDLINE_ZIP" -d "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools/"* "$SDK_DIR/cmdline-tools/latest/"
    rmdir "$SDK_DIR/cmdline-tools/cmdline-tools"
  fi

  yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null
  "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0"

  printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT_DIR/local.properties"
}

build_apk() {
  echo "Building debug APK"
  (cd "$ROOT_DIR" && ./gradlew assembleDebug)
}

connect_phone() {
  "$ADB" start-server

  if [[ -n "$PAIR_TARGET" ]]; then
    echo "Pairing with $PAIR_TARGET"
    "$ADB" pair "$PAIR_TARGET" "$PAIR_CODE"
  fi

  if [[ -n "$CONNECT_TARGET" ]]; then
    echo "Connecting to $CONNECT_TARGET"
    "$ADB" connect "$CONNECT_TARGET"
  fi

  echo "Connected devices:"
  "$ADB" devices -l
}

install_apk() {
  local apk="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$apk" ]]; then
    echo "APK was not found at $apk"
    exit 1
  fi

  echo "Installing $apk"
  "$ADB" install -r "$apk"
  echo "Launching DriveTouch Verifier"
  "$ADB" shell am start -n "$MAIN_ACTIVITY"
}

ensure_sdk
build_apk
connect_phone
install_apk
