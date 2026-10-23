#!/usr/bin/env bash
# Licensed under the Apache License, Version 2.0 (ObiWindu <Obi.wandu@proton.me>)
# Builds the Debian package for GraalMail with jpackage.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_DIR="${SCRIPT_DIR}/dist"

VERSION="$(sed -n 's/^version=//p' "${PROJECT_ROOT}/gradle.properties" | head -n 1)"
VERSION="${VERSION:-0.1.0}"

echo "Building GraalMail version ${VERSION}"

cd "${PROJECT_ROOT}"
./gradlew :desktop:packageDeb

mkdir -p "${OUTPUT_DIR}"
cp "${PROJECT_ROOT}"/desktop/build/dist/deb/*.deb "${OUTPUT_DIR}/"

echo "Debian package written to ${OUTPUT_DIR}"
