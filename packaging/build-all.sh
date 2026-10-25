#!/usr/bin/env bash
# Licensed under the Apache License, Version 2.0 (ObiWindu <Obi.wandu@proton.me>)
# Build script for creating the Linux release artifacts.
#
# The actual packaging is done by the Gradle tasks in :desktop. This script
# only orchestrates the build and collects the results in packaging/dist.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/dist"

VERSION="$(sed -n 's/^version=//p' "${PROJECT_ROOT}/gradle.properties" | head -n 1)"
VERSION="${VERSION:-0.1.0}"

echo "Building GraalMail version ${VERSION}"
cd "${PROJECT_ROOT}"

./gradlew :desktop:packageAppImageTarball :desktop:stageDesktopFiles
mkdir -p "${OUTPUT_DIR}"

cp "${PROJECT_ROOT}"/desktop/build/dist/graalmail-"${VERSION}"-linux-*.tar.gz "${OUTPUT_DIR}/"
cp -r "${PROJECT_ROOT}/desktop/build/dist/desktop-files/." "${OUTPUT_DIR}/"

if command -v dpkg-deb >/dev/null 2>&1; then
    ./gradlew :desktop:packageDeb
    cp "${PROJECT_ROOT}"/desktop/build/dist/deb/*.deb "${OUTPUT_DIR}/"
else
    echo "dpkg-deb not found, skipping the Debian package"
fi

if command -v rpmbuild >/dev/null 2>&1; then
    ./gradlew :desktop:packageRpm
    cp "${PROJECT_ROOT}"/desktop/build/dist/rpm/*.rpm "${OUTPUT_DIR}/"
else
    echo "rpmbuild not found, skipping the RPM package"
fi

echo "Packaging complete. Artifacts are in ${OUTPUT_DIR}"
