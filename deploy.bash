#!/bin/bash
#
# Copyright (c) 2023-2024 Joachim Ansorg.
# SPDX-License-Identifier: AGPL-3.0-or-later
#

set -e -x

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

VERSION="$(cat "$DIR/VERSION.txt")"
DOCKER_TAG="${1:-latest}"
echo "Building Version $VERSION with Docker tag $DOCKER_TAG"

./gradlew clean build
git tag --force "v$VERSION"
git push
git push --tags --force

if [[ -z $1 ]]; then
  gh release create "v$VERSION" --generate-notes ./build/libs/marketplace-*.jar
fi

echo "Publishing Docker containers..."
docker buildx create --use
docker buildx build --platform linux/amd64,linux/arm64 -f Dockerfile \
  -t "jansorg/jetbrains-marketplace-stats:$VERSION" \
  -t "jansorg/jetbrains-marketplace-stats:$DOCKER_TAG" \
  --push \
  .
