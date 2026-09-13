#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_COMMIT=54ed58499d7063136ed52dabf87d179d252425d0
ENGINE_DIR="$PROJECT_DIR/.build/sing-box-apple"
[[ "$(uname -s)" == Darwin ]] || { echo 'Apple builds require a Mac with Xcode.'; exit 1; }
command -v go >/dev/null || { echo 'Install Go 1.24.10 first.'; exit 1; }
xcodebuild -version
mkdir -p "$PROJECT_DIR/.build"
if [[ ! -d "$ENGINE_DIR/.git" ]]; then git clone --branch v1.12.12 --depth 1 https://github.com/SagerNet/sing-box.git "$ENGINE_DIR"; fi
cd "$ENGINE_DIR"
[[ "$(git rev-parse HEAD)" == "$ENGINE_COMMIT" ]] || { echo 'Unexpected native engine revision.'; exit 1; }
if ! git diff --quiet || ! git diff --cached --quiet; then echo 'Engine checkout has changes. Use a clean pinned checkout.'; exit 1; fi
cp "$PROJECT_DIR/apple/Engine/yay_health.go" experimental/libbox/yay_health.go
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
export PATH="$(go env GOPATH)/bin:$PATH"
go run ./cmd/internal/build_libbox -target apple -platform ios,macos
# ditto replaces the generated framework directory on repeat builds.
if [[ -d "$PROJECT_DIR/apple/Libbox.xcframework" ]]; then rm -rf "$PROJECT_DIR/apple/Libbox.xcframework"; fi
ditto Libbox.xcframework "$PROJECT_DIR/apple/Libbox.xcframework"
printf '%s\n' 'Apple native framework built; generate the Xcode project next.'
