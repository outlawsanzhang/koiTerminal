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

# socat -- linux_vm_manager uses it for now, and it runs with vsock access at boot. better not manage it with brew
# TODO: implement vsock-tcp forwarding yourself and don't depend on socat
while ! rpm-ostree install socat; do sleep 10; done

# Set up group for koiTerminal systemd services
groupadd koi-services

# https://secureblue.dev/post-install
echo -e 'y\ny\nn' | ujust set-kargs-hardening
# Note:
#   module.sig_enforce: potential upstream issue where this flag is left out
#   console=hvc0: see notes in step 5
rpm-ostree kargs --append-if-missing=module.sig_enforce=1 --append-if-missing=console=hvc0
# leave out --append-if-missing=ia32_emulation=0 for now

