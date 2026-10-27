#!/bin/bash
# Cleaning up Secureblue.

set -ex -o pipefail

# Reclaim space. Remove anything pending as well.
systemctl stop rpm-ostreed-automatic.timer
systemctl stop flatpak-system-update.timer
systemctl stop brew-update.timer
systemctl stop podman-auto-update.timer
rpm-ostree cancel
while ! rpm-ostree cleanup --base --repomd --rollback --pending; do
    sleep 10
done
podman system prune --all --force || true

# Ensure everything is alright
run0 --user droid ujust audit-secureblue | tee /dev/shm/audit.log
if grep -i "\(hardened kernel arguments\|signed image\).*FAIL" /dev/shm/audit.log; then
    echo "Setup incomplete."
    exit 1
fi

# Fixes for latest kernel not booting: provide option to boot into an older kernel

# Prepare for grub usage
echo "timeout=3" >> /boot/grub2/user.cfg

# Deploy LTS kernel
# dnf copr enable kwizart/kernel-longterm-6.18 fedora-44-aarch64 # this does not work yet as of 2026 Oct.
while ! dnf copr enable kwizart/kernel-longterm-6.12 fedora-43-aarch64 -y; do sleep 5; done
while ! rpm-ostree override remove kernel{,-core,-modules,-modules-extra} $(echo "--install kernel-longterm"{,-core,-modules,-modules-extra}) --install gnome-remote-desktop; do sleep 5; done
ostree admin status
rpm-ostree status


