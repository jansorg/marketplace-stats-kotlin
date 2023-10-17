#!/bin/bash
#
# Copyright (c) 2023 Joachim Ansorg.
# SPDX-License-Identifier: AGPL-3.0-or-later
#

set -e -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

VERSION="$(cat "$DIR/VERSION.txt")"
echo "Building Version $VERSION ..."

./gradlew clean build

git tag --force "v$VERSION"
git push --all
gh release create "v$VERSION" --generate-notes ./build/libs/marketplace-*.jar

docker build -f Dockerfile -t "jansorg/jetbrains-marketplace-stats:$VERSION" .
docker tag "jansorg/jetbrains-marketplace-stats:$VERSION" "jansorg/jetbrains-marketplace-stats:latest"
docker push jansorg/jetbrains-marketplace-stats

