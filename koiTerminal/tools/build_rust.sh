#!/bin/bash
set -e
cd "$(dirname "$0")/../rust"
cargo ndk -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release
