#!/bin/bash

set -exu

### Mount rootfs image, and chroot into with some extra setup.
###
### For cross-arch, configure qemu-user-static with following command:
### $ sudo docker run --rm --privileged multiarch/qemu-user-static --reset -p yes

check_sudo() {
	if [ "$EUID" -ne 0 ]; then
		echo "Please run as root." ; exit 1
	fi
}

show_help() {
	echo "Usage: sudo $0 [OPTION] \${rootfs_path}"
	echo "Mount rootfs, and chroot into it [sudo is required]"
	echo ""
	echo "Options:"
	echo "-b SRC:DST   Bind extra directory. Can be repeated."
	echo "-c COMMAND   Command to invoke via 'chroot /bin/bash -c \${your_command}'."
	echo "             [Default: use chroot default]"
}

check_sudo() {
	if [ "$EUID" -ne 0 ]; then
		echo "Please run as root." ; exit 1
	fi
}

parse_options() {
	while getopts ":b:c:" option; do
		case ${option} in
			b)
				chroot_mount+=("${OPTARG}")
				;;
			c)
				chroot_command="${OPTARG}"
				;;
			*)
				echo "Invalid option: $OPTARG" >&2
				show_help
				exit 1
				;;
		esac
	done

	shift $((OPTIND - 1))

	if [[ "$#" -ne 1 ]]; then
		echo "Specify rootfs path is required." >&2
		show_help
		exit 1
	fi

	chroot_rootfs="${1}"
}

mount_rootfs() {
	mkdir -p "${chroot_workspace}"

	mount "${chroot_rootfs}" "${chroot_workspace}"

	for arg in "${chroot_mount[@]}"; do
		local src=${arg%:*}
		local dst=${arg#*:}
		if [[ -z "${src}" || -z "${dst}" ]]; then
			echo "Failed to mount ${src} onto ${dst}" >&2
			exit 1
		fi
		mkdir -p "${chroot_workspace}/${dst}"
		mount --bind "${src}" "${chroot_workspace}/${dst}"
	done

	mount --rbind /dev "${chroot_workspace}/dev"
	mount --rbind /proc "${chroot_workspace}/proc"
	mount --rbind /sys "${chroot_workspace}/sys"

	local resolv="${chroot_workspace}/etc/resolv.conf"
	if [[ $(ls "${chroot_workspace}/etc/resolv.conf") ]]; then
		mv -v "${chroot_workspace}/etc/resolv.conf" "${chroot_workspace}/etc/resolv.conf.bak"
	else
		mkdir -p "${chroot_workspace}/etc"
	fi
	cp -vL "/etc/resolv.conf" "${chroot_workspace}/etc/resolv.conf"
}

enter_chroot() {
	if [[ -n "${chroot_command}" ]]; then
		chroot "${chroot_workspace}" /bin/bash -c "${chroot_command}"
	else
		chroot "${chroot_workspace}"
	fi
}

clean_up() {
	trap - EXIT

	if [[ $(ls "${chroot_workspace}/etc/resolv.conf.bak") ]]; then
		mv -v "${chroot_workspace}/etc/resolv.conf.bak" "${chroot_workspace}/etc/resolv.conf" || true
	fi
	rm -d ${chroot_workspace}/etc || true

	for arg in "${chroot_mount[@]}"; do
		local dst=${arg#*:}
		umount "${chroot_workspace}/${dst}" || true
		rm -r "${chroot_workspace}/${dst}" || true
	done

	if [[ -d "${chroot_workspace}" ]]; then
		umount -R -l "${chroot_workspace}" || true # -l fixes breakage when folder being busy
	fi

	rm -d "${chroot_workspace}"
}

trap clean_up EXIT

chroot_mount=()
chroot_workspace=$(mktemp -d)
chroot_command=""
chroot_rootfs=

check_sudo

parse_options "$@"
mount_rootfs
enter_chroot
