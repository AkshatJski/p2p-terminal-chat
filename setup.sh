#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# p2p-terminal-chat one-command setup (Unix / macOS / Linux)
#
# Usage:
#   bash setup.sh          # build + run
#   bash setup.sh --build  # build only
#
# Prerequisites: JDK 26 must be installed and on PATH.
# ----------------------------------------------------------------------------

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log()  { printf "${CYAN}==>${NC} %s\n" "$*"; }
ok()   { printf "${GREEN}✓${NC}  %s\n" "$*"; }
err()  { printf "${RED}✗${NC}  %s\n" "$*" >&2; }
info() { printf "${BOLD}%s${NC}\n" "$*"; }

BUILD_ONLY=false
[ "${1-}" = "--build" ] && BUILD_ONLY=true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_NAME="java-p2p-terminal-chat-1.1.0.jar"

# ---- Check Java ----
if ! command -v java &>/dev/null; then
  err "Java is not installed or not on PATH."
  echo ""
  echo "  Install JDK 26 from:"
  echo "    macOS:   brew install openjdk@26"
  echo "    Ubuntu:  sudo apt install openjdk-26-jdk"
  echo "    Windows: winget install EclipseAdoptium.Temurin.26.JDK"
  echo "    Or visit: https://adoptium.net"
  exit 1
fi

JAVA_VER="$(java -version 2>&1 | head -n1 | sed 's/.*"\([0-9]*\).*/\1/')"
if [ "$JAVA_VER" -lt 26 ] 2>/dev/null; then
  err "Java $JAVA_VER detected, but JDK 26+ is required."
  exit 1
fi
ok "Java $JAVA_VER detected"

# ---- Decide build tool ----
cd "$SCRIPT_DIR"

if [ -f "./mvnw" ]; then
  MVN_CMD="./mvnw"
  log "Using Maven Wrapper (./mvnw)"
else
  if ! command -v mvn &>/dev/null; then
    err "Maven is not installed and mvnw was not found."
    echo "  Install Maven: https://maven.apache.org/download.cgi"
    exit 1
  fi
  MVN_CMD="mvn"
  log "Using system Maven"
fi

chmod +x "$MVN_CMD" 2>/dev/null || true

# ---- Build ----
log "Building project..."
$MVN_CMD -q package -DskipTests
ok "Build complete → target/$JAR_NAME"

# ---- Run ----
if [ "$BUILD_ONLY" = true ]; then
  info "Build-only mode. To run:"
  echo "  java -jar target/$JAR_NAME"
  exit 0
fi

log "Starting p2p-terminal-chat..."
echo ""
java -jar "target/$JAR_NAME"
