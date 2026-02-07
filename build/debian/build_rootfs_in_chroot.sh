#!/bin/bash

set -ex

### Build raw images by proprocessing in chroot.
### Prefer using cloud-init for customization, and modify here only when required.

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

remove_packages() {
	rm /etc/kernel/postrm.d/zz-update-grub || true # fix: updating grub post-purge breaks in chroot, but we don't need grub
	apt purge -y linux-image-*
}

# cloud-init requires several minutes of extra delay for installing packages,
# Preinstall required packages here to reduce initial booting time.
install_packages() {
	INSTALL_PACKAGES=(
		kmod
		udev
		avahi-daemon
		avahi-utils
		libbpf-tools
		libnss-mdns
		procps
		pulseaudio
		systemd-zram-generator
	)
	INSTALL_PACKAGES_BOOKWORM_BACKPORTS=(
		weston
		xwayland
		mesa-vulkan-drivers
		libvulkan1
		vulkan-tools
	)

	echo "deb http://deb.debian.org/debian bookworm-backports main" >> /etc/apt/sources.list.d/bookworm-backports.list
	apt update || apt update
	DEBIAN_FRONTEND=noninteractive apt -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" upgrade -y
	apt install --no-install-recommends -y "${INSTALL_PACKAGES[@]}"
	apt install --no-install-recommends -t bookworm-backports -y "${INSTALL_PACKAGES_BOOKWORM_BACKPORTS[@]}"
}

# TODO: Install ttyd from debian package after it picks up our patches
install_ttyd() {
	cp --preserve=mode -vpR /mnt/ttyd/* /
}

modify_pre_cloud_init_configs() {
	# Since we boot directly with custom kernel, we wouldn't need EFI partition.
	# However, boot failed because of the error from /etc/fstab, so fix here.
	sed -i '\|/boot/efi|s|^|#|' /etc/fstab

	# LLMNR cause port notification (5355) before cloud-init can configure.
	# TODO: Move this to cloud-init, and add the port to the disallow-list.
	sed -i 's/#LLMNR=yes/LLMNR=no/' /etc/systemd/resolved.conf
}

remove_packages
install_packages
install_ttyd
modify_pre_cloud_init_configs
