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

# the libraries marketplace-client and marketplace-data, before the tag, the same version can't be published twice.
# Needs the Gradle properties jaDevUsername and jaDevPassword, e.g. in ~/.gradle/gradle.properties:
# the name and the secret of a Reposilite access token, which may write to https://maven.j-a.dev/releases
if [[ -z $1 ]]; then
  echo "Publishing the libraries to maven.j-a.dev..."
  ./gradlew publishAllPublicationsToJaDevRepository --no-configuration-cache
fi

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
