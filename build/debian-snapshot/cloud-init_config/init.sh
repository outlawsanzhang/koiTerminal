#!/bin/bash

set -ex

SCRIPT_PATH=$(readlink -f -- "${0}")
SCRIPT_DIR=$(dirname -- ${SCRIPT_PATH});

LOCALDEBS="${SCRIPT_DIR}/localdebs"
LOCALFILES="${SCRIPT_DIR}/root_files"

install_localdebs() {
    if [ -d "$LOCALDEBS" ]; then
        find "$LOCALDEBS" -name "*.deb" -print0 | xargs -0 -r dpkg -i
    fi
}

_copy_files() {
    cp -vR "${LOCALFILES}"/* /
    sed -i -e "s;agetty -o ';agetty -a droid -o '-f ;g" /usr/lib/systemd/system/serial-getty@.service # auto login
	mkdir -p /mnt/internal
	ln -s /dev/vda2 /mnt/internal/ca.crt
}

_restart_services() {
    CONFIG_CHANGED_SERVICES=(
        attach-cidata.service
        avahi_ttyd.service
        backup_mount.service
        ttyd.service
        ttyd_uds.service
        ttyd_vsock_bridge.service
        linux_vm_manager.service
    )
    systemctl enable --now "${CONFIG_CHANGED_SERVICES[@]}"
}

apply_avf_configs() {
    _copy_files
    _restart_services
}

install_localdebs
apply_avf_configs
