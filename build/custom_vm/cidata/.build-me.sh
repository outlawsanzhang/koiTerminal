#!/bin/bash

set -e

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
OUTPUT="${1:-$SCRIPT_DIR/../cidata.iso}"

cd "$SCRIPT_DIR"
BUILD_REQUIREMENTS="root_files/usr/local/bin/linux_vm_manager root_files/usr/local/bin/tun2proxy-bin root_files/etc/systemd/system/"
if ! ls -d $(echo $BUILD_REQUIREMENTS) > /dev/null; then
    echo '
        Please find the following files/folders:
            '$BUILD_REQUIREMENTS'
        Then run me again.
    '
    exit 1
fi

set -x

# Actual building
chmod 755 root_files/usr/local/bin/{linux_vm_manager,tun2proxy-bin}
genisoimage -output "$OUTPUT" -V cidata -J -r ../cidata/
