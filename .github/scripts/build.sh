#!/bin/bash
set -e
rustup target add aarch64-linux-android x86_64-linux-android
cargo binstall cargo-ndk@4.1.2
cd koiTerminal
./tools/setup.sh
./tools/build_rust.sh
./gradlew :app:assembleDebug
