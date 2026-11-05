#!/bin/bash

set -ex -o pipefail

# restore deleted files
rsync -a /usr/etc/containers/policy.json /etc/containers/policy.json
rsync -a /usr/etc/containers/registries.d/secureblue.yaml /etc/containers/registries.d/secureblue.yaml

systemctl stop rpm-ostreed-automatic.timer
systemctl stop flatpak-system-update.timer
systemctl stop brew-update.timer
systemctl stop podman-auto-update.timer
rpm-ostree cancel

# Prepare for grub usage
echo "timeout=3" >> /boot/grub2/user.cfg

# Following https://secureblue.dev/post-install

# Changing kargs should happen before kernel change, because the kernel change
# will be applied on each rpm-ostree operation, and each apply will take hours.
echo -e 'y\ny\nn' | ujust set-kargs-hardening
rpm-ostree kargs --append-if-missing=console=hvc0 # (see ks.cfg)
# leave out --append-if-missing=module.sig_enforce=1 # this omission was fixed upstream
# leave out --append-if-missing=ia32_emulation=0 for now
