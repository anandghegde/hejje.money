#!/usr/bin/env bash
# goreleaser-style multi-platform build for the hejje TUI.
set -euo pipefail
VERSION="${1:-dev}"
OUT="dist"
rm -rf "$OUT"; mkdir -p "$OUT"
for target in linux/amd64 linux/arm64 darwin/amd64 darwin/arm64; do
  os="${target%/*}"; arch="${target#*/}"
  echo "building $os/$arch"
  GOOS="$os" GOARCH="$arch" CGO_ENABLED=0 \
    go build -ldflags "-s -w -X main.version=$VERSION" -o "$OUT/hejje-$os-$arch" ./cmd/hejje
done
echo "artifacts in $OUT/"
