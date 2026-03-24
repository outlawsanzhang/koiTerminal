#!/bin/bash
# This part is from https://github.com/secureblue/secureblue/blob/live/docs/example.butane#L22
# and the license is Apache-2.0: https://github.com/secureblue/secureblue/blob/live/LICENSE
# Doing this rebases onto Secureblue on first login of user droid after install.

set -ex -o pipefail

systemctl disable --now zincati.service 2>/dev/null || true
systemctl stop rpm-ostreed-automatic.timer rpm-ostreed-automatic.service 2>/dev/null || true
# Leaving out --enforce-container-sigpolicy for now as it is not working (need to modify policy and import keys)
while ! bootc switch ghcr.io/secureblue/silverblue-main-hardened:latest; do
    sleep 10
done

