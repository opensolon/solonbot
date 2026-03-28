#!/bin/bash
#
# SolonCode CLI Installer
# Usage: curl -fsSL https://solon.noear.org/soloncode/setup.sh | bash
#

set -e

VERSION="v2026.4.1"
PACKAGE_URL="https://solon.noear.org/soloncode/bin/soloncode-cli-bin-${VERSION}.tar.gz"
TEMP_DIR="/tmp/soloncode-install"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

cleanup() {
    rm -rf "$TEMP_DIR"
}

trap cleanup EXIT

# Create temp directory
mkdir -p "$TEMP_DIR"

info "Downloading SolonCode CLI ${VERSION}..."

# Download package
if command -v curl &> /dev/null; then
    curl -fsSL "$PACKAGE_URL" -o "$TEMP_DIR/package.tar.gz"
elif command -v wget &> /dev/null; then
    wget -q "$PACKAGE_URL" -O "$TEMP_DIR/package.tar.gz"
else
    error "curl or wget is required"
    exit 1
fi

info "Extracting package..."

# Extract
tar -xzf "$TEMP_DIR/package.tar.gz" -C "$TEMP_DIR"

# Find install.sh
INSTALL_SCRIPT=$(find "$TEMP_DIR" -name "install.sh" -type f | head -1)

if [ -z "$INSTALL_SCRIPT" ]; then
    error "install.sh not found in package"
    exit 1
fi

info "Running installer..."

# Run installer
bash "$INSTALL_SCRIPT"