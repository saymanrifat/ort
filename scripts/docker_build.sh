#!/bin/bash
#
# Copyright (C) 2020 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
# License-Filename: LICENSE

set -e -o pipefail

GIT_ROOT=$(git rev-parse --show-toplevel)
GIT_REVISION=$($GIT_ROOT/gradlew -q properties --property version | sed -nr "s/version: (.+)/\1/p")

echo "Setting ORT_VERSION to $GIT_REVISION."

# ---------------------------
# image_build function
# Usage ( position paramenters):
# image_build <target_name> <tag_name> <version> <extra_args...>

image_build() {
    local target
    local name
    local version
    target="$1"
    shift
    name="$1"
    shift
    version="$1"
    shift

    docker buildx build \
        -f "$GIT_ROOT/Dockerfile" \
        --target "$target" \
        --tag "${DOCKER_IMAGE_ROOT}/$name:$version" \
        --tag "${DOCKER_IMAGE_ROOT}/$name:latest" \
        --build-context "base=docker-image://${DOCKER_IMAGE_ROOT}/base:latest" \
        "$@" .
}

# Base
# shellcheck disable=SC1091
. .ortversions/base.versions
image_build ort-base-image base "${JAVA_VERSION}-jdk-${UBUNTU_VERSION}" \
    --build-arg UBUNTU_VERSION="$UBUNTU_VERSION" \
    --build-arg JAVA_VERSION="$JAVA_VERSION" \
    "$@"

# Python
# shellcheck disable=SC1091
. .ortversions/python.versions
image_build python python "$PYTHON_VERSION" \
    --build-arg PYTHON_VERSION="$PYTHON_VERSION" \
    --build-arg CONAN_VERSION="$CONAN_VERSION" \
    --build-arg PYTHON_INSPECTOR_VERSION="$PYTHON_INSPECTOR_VERSION" \
    --build-arg PYTHON_PIPENV_VERSION="$PYTHON_PIPENV_VERSION" \
    --build-arg PYTHON_POETRY_VERSION="$PYTHON_POETRY_VERSION" \
    --build-arg PIPTOOL_VERSION="$PIPTOOL_VERSION" \
    --build-arg SCANCODE_VERSION="$SCANCODE_VERSION" \
    "$@"

# Nodejs
# shellcheck disable=SC1091
. .ortversions/nodejs.versions
image_build nodejs nodejs "$NODEJS_VERSION" \
    --build-arg NODEJS_VERSION="$NODEJS_VERSION" \
    --build-arg BOWER_VERSION="$BOWER_VERSION" \
    --build-arg NPM_VERSION="$NPM_VERSION" \
    --build-arg PNPM_VERSION="$PNPM_VERSION" \
    --build-arg YARN_VERSION="$YARN_VERSION" \
    "$@"

# Ort
image_build ortbin ortbin "$GIT_REVISION" \
    --build-arg ORT_VERSION="$GIT_REVISION" \
    "$@"

# Runtime ORT image
image_build run ort "$GIT_REVISION" \
    --build-context "python=docker-image://${DOCKER_IMAGE_ROOT}/python:latest" \
    --build-arg NODEJS_VERSION="$NODEJS_VERSION" \
    --build-context "nodejs=docker-image://${DOCKER_IMAGE_ROOT}/nodejs:latest" \
    --build-context "ortbin=docker-image://${DOCKER_IMAGE_ROOT}/binaries:latest" \
"$@"

[ -z "$ALL_LANGUAGES" ] && exit 0

# Build adjacent language containers

# Rust
# shellcheck disable=SC1091
. .ortversions/rust.versions
image_build rust rust "$RUST_VERSION" \
    --build-arg RUST_VERSION="$RUST_VERSION" \
    "$@"
