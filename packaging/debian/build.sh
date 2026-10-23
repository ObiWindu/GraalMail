#!/bin/bash
# Licensed under the Apache License, Version 2.0 (ObiWindu <Obi.wandu@proton.me>)
# Build script for creating Debian package

set -e

# Get version from gradle.properties or use default
VERSION=$(grep -E "^version\s*=" ../gradle.properties | cut -d'=' -f2 | tr -d ' ' || echo "0.1.0")

echo "Building GraalMail version $VERSION"

# Build the distribution zip
cd ..
./gradlew :desktop:distZip

# Copy zip to packaging directory
cp desktop/build/distributions/graalmail.zip packaging/debian/graalmail-${VERSION}.zip

# Build the package
cd packaging/debian
dpkg-buildpackage -us -uc -b

echo "Debian package created in packaging/debian/"