#!/bin/bash
# Configuring Secureblue.

set -ex -o pipefail

# ujust toggle-mac-randomization # This is probably not needed for a VM

# For DNS, sometimes resolved seems to work better
# ujust dns-selector resolver resolved
# Switch to a signed image (the original is installed unsigned)
while ! rpm-ostree rebase ostree-image-signed:docker://ghcr.io/secureblue/silverblue-main-hardened:latest; do
    sleep 10
done
# Reset DNS to default
# ujust dns-selector reset

