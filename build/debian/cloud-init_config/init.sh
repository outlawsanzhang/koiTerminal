#!/bin/bash

set -ex

SCRIPT_PATH=$(readlink -f -- "${0}")
SCRIPT_DIR=$(dirname -- ${SCRIPT_PATH});

LOCALDEBS="${SCRIPT_DIR}/localdebs"
LOCALFILES="${SCRIPT_DIR}/root_files"

install_localdebs() {
	dpkg -i "${LOCALDEBS}"/*.deb
}

_copy_files() {
	cp -vpR "${LOCALFILES}"/* /
	sed -i -e "s;agetty -o ';agetty -a droid -o '-f ;g" /usr/lib/systemd/system/serial-getty@.service # auto login
	mkdir -p /mnt/internal
	ln -s /dev/vda3 /mnt/internal/ca.crt
}

_restart_services() {
	CONFIG_CHANGED_SERVICES=(
		avahi_ttyd.service
		backup_mount.service


		ttyd.service
	)
	systemctl enable --now "${CONFIG_CHANGED_SERVICES[@]}"
}

apply_avf_configs() {
	_copy_files
	_restart_services
}

install_localdebs
apply_avf_configs
