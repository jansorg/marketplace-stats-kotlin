#!/bin/bash
#
# Copyright (c) 2023-2024 Joachim Ansorg.
# SPDX-License-Identifier: AGPL-3.0-or-later
#

set -e -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

VERSION="$(cat "$DIR/VERSION.txt")"
echo "Building Version $VERSION ..."

./gradlew clean build
git tag --force "v$VERSION"
git push
git push --tags
gh release create "v$VERSION" --generate-notes ./build/libs/marketplace-*.jar

echo "Publishing Docker containers..."
docker buildx create --use
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile \
  -t "jansorg/jetbrains-marketplace-stats:$VERSION" \
  -t "jansorg/jetbrains-marketplace-stats:latest" \
  --push \
  .

