#!/bin/bash
# Cleaning up Secureblue.

set -ex -o pipefail

# Pin latest kernel deployment
# ostree admin pin booted
# ostree admin status
# rpm-ostree status
# ostree admin pin pending # You can't.

systemctl stop rpm-ostreed-automatic.timer
systemctl stop flatpak-system-update.timer
systemctl stop brew-update.timer
systemctl stop podman-auto-update.timer
rpm-ostree cancel
# Reclaim space. Remove anything pending as well.
while ! rpm-ostree cleanup --base --repomd --rollback --pending; do
    sleep 10
done
podman system prune --all --force || true
ostree admin status
rpm-ostree status

# Ensure everything is alright
run0 --user droid ujust audit-secureblue | tee /dev/shm/audit.log
if grep -i "\(hardened kernel arguments\|signed image\).*FAIL" /dev/shm/audit.log; then
    echo "Setup incomplete."
    exit 1
fi

################################################################################
# Installing and configuring software

# Multi-tabbing essentials
# Directly invoking brew-proxy
while ! BREW_PROXY_NONINTERACTIVE=1 /usr/bin/brew-proxy install -y screen tmux; do sleep 10; done

# Using resolved for compatibility with tunneling over SOCKS5
ujust dns-selector resolver resolved

# Set up group for koiTerminal systemd services
groupadd --system koi-services
# Set up group for GUI login without passwd
groupadd --system nopasswdlogin
# Set up user for handling koi-tun2proxy
useradd --system koi-tun2proxy

# Set up permanent tunnel for koi-tun2proxy
while ! nmcli \
    connection add save yes type tun \
    ifname koi con-name koi \
    tun.mode tun tun.owner $(id -u koi-tun2proxy) tun.pi yes \
    ipv4.method manual ip4 10.0.0.33/24 ipv4.gateway 10.0.0.1 ipv4.dns 10.0.0.1 ipv4.routes "0.0.0.0/0" \
    ipv6.method manual ipv6.addr-gen-mode stable-privacy ipv6.routes "::0/0"
do
    systemctl start NetworkManager || true
done

# System update timing -- more aggressive and upfront for use as a VM
# First start 5-15 minutes after boot
sed -i -e 's/^\(RandomizedDelaySec\)=.*/\1=10m\nOnBootSec=5m/' -e 's/^\(RestartSec\)=.*/\1=5m/' /etc/systemd/system/rpm-ostreed-automatic.timer.d/override.conf
# Keep restarting every 5 minutes on failure, max 12 tries per 24h
sed -i -e 's/^\(StartLimitIntervalSec\)=.*/\1=24h/' -e 's/^\(StartLimitBurst\)=.*/\1=12/' -e 's/^\(RestartSec\)=.*/\1=5m/' /etc/systemd/system/rpm-ostreed-automatic.service.d/override.conf

################################################################################
# Cleanup

# Clean up installing scripts
rm -f /var/home/droid/*install_secureblue.sh

# Set up first-use recommendations
sed -i 's;/var/home/droid/run_install_secureblue.sh;/var/home/droid/first_use_secureblue.sh;g' /var/home/droid/.bash_profile
cat <<'EOF' > /var/home/droid/first_use_secureblue.sh
    echo 'Running `ujust audit-secureblue` to provide post-install recommendations...'
    ujust audit-secureblue --skip flatpak
    echo 'Audit complete. Please refer to the recommendations above.'
    echo
    sed -i '/\/var\/home\/droid\/first_use_secureblue.sh/d' /var/home/droid/.bash_profile
    rm -f /var/home/droid/first_use_secureblue.sh
EOF
chmod a+x /var/home/droid/first_use_secureblue.sh
chown droid:droid /var/home/droid/first_use_secureblue.sh

# Clean up gdm.service core dumps
rm -f /var/lib/systemd/coredump/*
