#!/bin/bash
# Setting up Secureblue kernel arguments.

set -ex -o pipefail

# restore deleted files
rsync -a /usr/etc/containers/policy.json /etc/containers/policy.json
rsync -a /usr/etc/containers/registries.d/secureblue.yaml /etc/containers/registries.d/secureblue.yaml

systemctl stop rpm-ostreed-automatic.timer
systemctl stop flatpak-system-update.timer
systemctl stop brew-update.timer
systemctl stop podman-auto-update.timer
rpm-ostree cancel

# Multi-tabbing essentials
# Directly invoking brew-proxy
while ! BREW_PROXY_NONINTERACTIVE=1 /usr/bin/brew-proxy install -y screen tmux; do sleep 10; done

# Using resolved for compatibility with tunneling over SOCKS5
ujust dns-selector resolver resolved

# Set up group for koiTerminal systemd services
groupadd koi-services

# https://secureblue.dev/post-install
echo -e 'y\ny\nn' | ujust set-kargs-hardening
# rpm-ostree kargs --append-if-missing=module.sig_enforce=1 # this omission was fixed upstream
# leave out --append-if-missing=ia32_emulation=0 for now
# leave out --append-if-missing=console=hvc0 (see ks.cfg)

