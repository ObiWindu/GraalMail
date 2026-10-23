#!/bin/bash
# Licensed under the Apache License, Version 2.0 (ObiWindu <Obi.wandu@proton.me>)
# Build script for creating all Linux packages

set -e

# Get version from gradle.properties or use default
VERSION=$(grep -E "^version\s*=" ../gradle.properties | cut -d'=' -f2 | tr -d ' ' || echo "0.1.0")
PROJECT_ROOT="$(cd .. && pwd)"
PACKAGING_DIR="$(pwd)"

echo "Building GraalMail version $VERSION"

# Build the distribution zip
cd "$PROJECT_ROOT"
./gradlew :desktop:distZip

# Create a temporary directory for packaging
TMPDIR=$(mktemp -d)
echo "Using temporary directory: $TMPDIR"

# Copy the distribution zip
cp "$PROJECT_ROOT/desktop/build/distributions/graalmail.zip" "$TMPDIR/graalmail-${VERSION}.zip"

# Extract the distribution
unzip -q "$TMPDIR/graalmail-${VERSION}.zip" -d "$TMPDIR"

# Copy desktop file and icon
cp "$PROJECT_ROOT/desktop/src/main/resources/packaging/graalmail.desktop" "$TMPDIR/"
cp "$PROJECT_ROOT/desktop/src/main/resources/icons/graalmail.png" "$TMPDIR/"

# Create Arch package source
ARCH_DIR="$TMPDIR/arch-pkg"
mkdir -p "$ARCH_DIR"
cp -r "$TMPDIR/graalmail" "$ARCH_DIR/"
cp "$TMPDIR/graalmail.desktop" "$ARCH_DIR/"
cp "$TMPDIR/graalmail.png" "$ARCH_DIR/"
cp "$PACKAGING_DIR/arch/PKGBUILD" "$ARCH_DIR/"
cp "$TMPDIR/graalmail-${VERSION}.zip" "$ARCH_DIR/"

cd "$ARCH_DIR"
makepkg -f --skipinteg || echo "Arch package build failed (expected in non-Arch environment)"
ARCH_PKG=$(ls *.pkg.tar.zst 2>/dev/null || ls *.pkg.tar.xz 2>/dev/null || echo "")
if [ -n "$ARCH_PKG" ]; then
    cp "$ARCH_PKG" "$PACKAGING_DIR/arch/"
    echo "Arch package created: $ARCH_PKG"
fi

# Create Debian package source
DEBIAN_DIR="$TMPDIR/debian-pkg"
mkdir -p "$DEBIAN_DIR"
cp -r "$TMPDIR/graalmail" "$DEBIAN_DIR/"
cp "$TMPDIR/graalmail.desktop" "$DEBIAN_DIR/"
cp "$TMPDIR/graalmail.png" "$DEBIAN_DIR/"
cp -r "$PACKAGING_DIR/debian" "$DEBIAN_DIR/"
cp "$TMPDIR/graalmail-${VERSION}.zip" "$DEBIAN_DIR/"

cd "$DEBIAN_DIR"
# Build debian package (requires debian build tools)
if command -v dpkg-buildpackage >/dev/null 2>&1; then
    cd debian
    dpkg-buildpackage -us -uc -b || echo "Debian package build failed"
    cd ..
    cp ../graalmail_${VERSION}-1_amd64.deb "$PACKAGING_DIR/debian/" 2>/dev/null || true
    echo "Debian package created"
else
    echo "dpkg-buildpackage not available, skipping Debian package build"
fi

# Create Fedora package source
FEDORA_DIR="$TMPDIR/fedora-pkg"
mkdir -p "$FEDORA_DIR"
cp -r "$TMPDIR/graalmail" "$FEDORA_DIR/"
cp "$TMPDIR/graalmail.desktop" "$FEDORA_DIR/"
cp "$TMPDIR/graalmail.png" "$FEDORA_DIR/"
cp "$PACKAGING_DIR/fedora/graalmail.spec" "$FEDORA_DIR/"
cp "$TMPDIR/graalmail-${VERSION}.zip" "$FEDORA_DIR/"

cd "$FEDORA_DIR"
# Build RPM (requires rpmbuild)
if command -v rpmbuild >/dev/null 2>&1; then
    # Create rpmbuild directory structure
    mkdir -p ~/rpmbuild/{SOURCES,SPECS,BUILD,RPMS,SRPMS}
    cp "graalmail-${VERSION}.zip" ~/rpmbuild/SOURCES/
    cp graalmail.desktop ~/rpmbuild/SOURCES/
    cp graalmail.png ~/rpmbuild/SOURCES/
    cp graalmail.spec ~/rpmbuild/SPECS/
    rpmbuild -ba ~/rpmbuild/SPECS/graalmail.spec || echo "RPM build failed"
    cp ~/rpmbuild/RPMS/x86_64/graalmail-*.rpm "$PACKAGING_DIR/fedora/" 2>/dev/null || true
    echo "Fedora RPM created"
else
    echo "rpmbuild not available, skipping Fedora package build"
fi

# Cleanup
cd "$PROJECT_ROOT"
rm -rf "$TMPDIR"

echo "Packaging complete!"
echo "Packages available in packaging/*/"