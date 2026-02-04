#!/bin/bash
#
# ==============================================================================
# Ferrochrome Rootfs Customization Script (Chroot-side)
# ==============================================================================
#
# This script runs inside the guest chroot environment to perform final
# configurations and package installations.
#
# Key Responsibilities:
# 1. Removes unnecessary default kernel packages to save space.
# 2. Pre-installs required packages to reduce initial boot time.
# 3. Optimizes dpkg/apt performance for faster builds.
# 4. Configures system services and network settings for AVF compatibility.
# 5. Cleans up build-time optimizations and temporary files.
#
# This script is invoked by build_internal.sh via chroot_rootfs.sh.
# ==============================================================================

set -eo pipefail

### --- Configuration --- ###

# Essential packages for the guest environment
GUEST_PACKAGES=(
  kmod
  udev
  libbpf-tools
  libvulkan1
  mesa-vulkan-drivers
  procps
  pulseaudio
  systemd-zram-generator
  vulkan-tools
  weston
  wl-clipboard
  xwayland
)

### --- Functions --- ###

# Remove default kernel packages to make room for our custom kernel
remove_default_kernels() {
  echo "--- Removing default kernel packages ---"
  apt purge -y linux-image-*
  rm /etc/kernel/postrm.d/zz-update-grub || true # fix: updating grub post-purge breaks in chroot, but we don't need grub
}

# Install required packages and optimize the process
install_packages() {
  echo "--- Installing guest packages ---"
  export DEBIAN_FRONTEND=noninteractive

  # Build-time optimization: keep downloaded debs in the cache for host-side sharing
  echo 'Binary::apt::APT::Keep-Downloaded-Packages "true";' > /etc/apt/apt.conf.d/01keep-debs

  # Optimization: exclude unnecessary files to speed up unpacking and save space
  # (Note: force-unsafe-io is omitted here as eatmydata handles it more effectively)
  mkdir -p /etc/dpkg/dpkg.cfg.d
  cat <<EOF > /etc/dpkg/dpkg.cfg.d/99-slim-image
path-exclude=/usr/share/doc/*
path-exclude=/usr/share/man/*
path-exclude=/usr/share/locale/*
path-exclude=/usr/share/info/*
path-exclude=/usr/share/lintian/*
EOF

  # Prevent services from starting and suppress udev triggers during installation
  echo -e '#!/bin/sh\nexit 101' > /usr/sbin/policy-rc.d
  chmod +x /usr/sbin/policy-rc.d

  # Create a dummy udevadm and systemd-hwdb via dpkg-divert to skip triggers
  dpkg-divert --add --rename --divert /usr/bin/udevadm.real /usr/bin/udevadm
  echo -e '#!/bin/sh\necho "Skipping udevadm $*"' > /usr/bin/udevadm
  chmod +x /usr/bin/udevadm

  dpkg-divert --add --rename --divert /usr/bin/systemd-hwdb.real /usr/bin/systemd-hwdb
  echo -e '#!/bin/sh\necho "Skipping systemd-hwdb $*"' > /usr/bin/systemd-hwdb
  chmod +x /usr/bin/systemd-hwdb

  echo "Updating package lists..."
  apt update || apt update

  echo "Installing eatmydata for faster build-time installations..."
  apt install -y eatmydata

  echo "Upgrading existing packages..."
  eatmydata apt -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" upgrade -y

  echo "Installing required guest components..."
  eatmydata apt install --no-install-recommends -y "${GUEST_PACKAGES[@]}"
}

# Apply AVF-specific configurations and cleanup build-time tools
apply_custom_configs() {
  echo "--- Applying system configurations ---"

  # Fix /etc/fstab: comment out EFI partition as we boot directly with custom kernel
  sed -i '\|/boot/efi|s|^|#|' /etc/fstab

  # Disable LLMNR to prevent early port notifications (port 5355)
  sed -i 's/#LLMNR=yes/LLMNR=no/' /etc/systemd/resolved.conf

  # Disable systemd-networkd-wait-online.service to prevent boot delays (2-minute timeout)
  systemctl disable systemd-networkd-wait-online.service
  systemctl mask systemd-networkd-wait-online.service

  echo "Cleaning up build-time optimizations..."
  apt purge -y eatmydata

  # Restore udevadm/systemd-hwdb and remove temporary configs
  rm -f /usr/bin/udevadm
  dpkg-divert --remove --rename /usr/bin/udevadm
  rm -f /usr/bin/systemd-hwdb
  dpkg-divert --remove --rename /usr/bin/systemd-hwdb
  rm -f /usr/sbin/policy-rc.d
  rm -f /etc/apt/apt.conf.d/01keep-debs
  rm -f /etc/dpkg/dpkg.cfg.d/99-slim-image
}

# Install ttyd from the bind-mounted directory
install_ttyd() {
  if [ -d "/mnt/ttyd" ]; then
    echo "--- Installing ttyd binary ---"
    cp --preserve=mode -vpR /mnt/ttyd/* /
  fi
}

### --- Main Execution --- ###

remove_default_kernels
install_packages
install_ttyd
apply_custom_configs

echo "Rootfs customization completed successfully."
