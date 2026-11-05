#!/bin/bash

set -ex -o pipefail

systemctl stop rpm-ostreed-automatic.timer
systemctl stop flatpak-system-update.timer
systemctl stop brew-update.timer
systemctl stop podman-auto-update.timer
rpm-ostree cancel

# Deploy LTS kernel. This takes hours and should happen after all other rpm-ostree operations.
# dnf copr enable kwizart/kernel-longterm-6.18 fedora-44-aarch64 # this does not work yet as of 2026 Oct.
while ! dnf copr enable kwizart/kernel-longterm-6.12 fedora-43-aarch64 -y; do sleep 5; done
while ! rpm-ostree override remove kernel{,-core,-modules,-modules-extra} $(echo "--install kernel-longterm"{,-core,-modules,-modules-extra}) --install gnome-remote-desktop; do sleep 5; done
ostree admin status
rpm-ostree status
