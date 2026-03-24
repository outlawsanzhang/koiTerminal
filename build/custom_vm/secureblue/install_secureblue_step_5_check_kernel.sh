#!/bin/bash
# Cleaning up Secureblue.

set -ex -o pipefail

# Reclaim space. Remove anything pending as well.
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

# Deploy older kernel
# TODO: are rpm files downloaded from koji.fedoraproject.org verified when installed?
KVER="6.12.15"
KTAG="200.fc41"
pushd /dev/shm > /dev/null
for package in kernel{,-core,-modules,-modules-core,-modules-extra}; do
    wget "https://kojipkgs.fedoraproject.org//packages/kernel/${KVER}/${KTAG}/aarch64/${package}-${KVER}-${KTAG}.aarch64.rpm"
done
popd > /dev/null
rpm-ostree override replace /dev/shm/kernel{,-core,-modules,-modules-core,-modules-extra}-"${KVER}-${KTAG}.aarch64.rpm"
ostree admin status
rpm-ostree status

# Alt: install kernel from file
# mount /dev/vdd /mnt
# rpm-ostree override replace /mnt/kernel-*.rpm
# To revert, run: rpm-ostree override reset kernel{,-core,-modules,-modules-core,-modules-extra}
# See: https://discussion.fedoraproject.org/t/dracut-and-ostree/731/5

