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

# Install pkexec wrapper for gnome-remote-desktop
cat <<"EOFEOF" > /usr/local/bin/pkexec
#!/usr/bin/bash

# Copyright 2025 The Secureblue Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

show_help() {
    /usr/bin/cat <<EOF
pkexec --version |
       --help |
       --disable-internal-agent |
       [--keep-cwd] [--user username] [PROGRAM] [ARGUMENTS...]
WARNING: This run0 convenience wrapper for pkexec is part of secureblue. \
Unlike pkexec, the --disable-internal-agent switch is discarded. run0 also \
inherits the environment, rather than running in a minimal one.
EOF
}

keep_cwd=0
target_user="root"

# Parse arguments.
while [[ $# -gt 0 ]]; do
    case "$1" in
        --help)
            show_help
            exit 0
            ;;

        --version)
            # Just in case --version output is used to detect pkexec presence.
            echo "pkexec version 126"
            echo "wrapper: spoofed pkexec version for compatibility" >&2
            exit 0
            ;;

        --disable-internal-agent)
            # Ignored intentionally.
            echo "wrapper: ignored option '--disable-internal-agent'" >&2
            shift
            ;;

        --keep-cwd)
            keep_cwd=1
            shift
            ;;

        --user)
            if [[ $# -lt 2 ]]; then
                echo "option '--user' requires an argument" >&2
                exit 1
            fi
            target_user="$2"
            shift 2
            ;;

        --user=*)
            target_user="${1#--user=}"
            shift
            ;;

        --)
            shift
            break
            ;;

        --*)
            echo "wrapper: unrecognized option '$1'"
            show_help
            exit 1
            ;;

        *)
            # The first non-option is the program to run.
            break
            ;;
    esac
done

program=""
if [[ $# -gt 0 ]]; then
    program="$1"
    shift
fi

if [[ "${target_user}" != "gnome-remote-desktop" ]] && [[ "${program}" != "/usr/libexec/gnome-remote-desktop-enable-service" ]]; then
    echo "pkexec wrapper: this script is not supported by Secureblue." >&2
    echo "It is not guaranteed to work and is only included in the VM image to" >&2
    echo "service the gnome-remote-desktop user." >&2
    echo "" >&2
    echo "If you accept its status of no support and would like to assume its" >&2
    echo "associated risks by using it, the 'exit 127' line needs to be removed." >&2
    exit 127
fi

# Start building run0 arguments.
cmd=("/usr/bin/run0" "--background=" "--user=${target_user}")

# pkexec behaviour is to run in target username's homedir unless --keep-cwd is
# passed, so we find ~target_user and pass it to run0 as --chdir.
target_user_home=""
if [[ $keep_cwd -eq 0 ]]; then
    target_user_home=$(/usr/bin/getent passwd "$target_user" | cut -d: -f6)
    if [[ -z "$target_user_home" ]]; then
        echo "wrapper: The home directory of $target_user could not be found. Try --keep-cwd." >&2
        exit 1
    fi
    cmd+=("--chdir=${target_user_home}")
fi

# If $program is not specified, the default shell will be run.
[[ -n "$program" ]] && cmd+=("--" "$program")
cmd+=("$@")

echo "wrapper: running '${cmd[@]}'" >&2
exec "${cmd[@]}"
EOFEOF
chmod 755 /usr/local/bin/pkexec

# Set up gnome-remote-desktop
GRDCERTDIR="/var/lib/gnome-remote-desktop/.cert"
mkdir -p "${GRDCERTDIR}"
/usr/bin/openssl req -new -newkey rsa:4096 -days 7200 -nodes -x509 -subj "/C=SE/ST=NONE/L=NONE/O=GNOME/CN=gnome.org" -out "${GRDCERTDIR}/grd-tls.crt" -keyout "${GRDCERTDIR}/grd-tls.key" # Temp. This needs to be overwritten during password setup
chown -R gnome-remote-desktop:gnome-remote-desktop "${GRDCERTDIR}"
grdctl --system rdp set-tls-cert "${GRDCERTDIR}/grd-tls.crt"
grdctl --system rdp set-tls-key "${GRDCERTDIR}/grd-tls.key"
grdctl --system rdp disable-view-only
# Will run these with host-provided password in systemd
#   grdctl --system rdp set-credentials droid $(cat passwd.pipe)
#   systemctl restart gnome-remote-desktop
/home/linuxbrew/.linuxbrew/bin/tmux new-session -s grdctl -y 10 -d 'set -x; while grdctl --system status | grep -Pzo "RDP:\s*Status:\s*disabled"; do grdctl --system rdp enable; done' # this needs an interactive pty because they are doing `pkexec --user root` within `pkexec --user gnome-remote-desktop` which needs authentication
while /home/linuxbrew/.linuxbrew/bin/tmux capture-pane -t 'grdctl:^' -p; do sleep 5; done # monitor tmux window and wait till it exits
while ! systemctl enable gnome-remote-desktop; do sleep 5; done
grdctl --system status || true
# Allow admin features over remote desktop
cat <<EOF > /etc/polkit-1/rules.d/50-remote-auth-admin.rules
polkit.addRule(function(action, subject) {
    if (action.id.indexOf("org.debian.pcsc-lite.access") === 0) {
        return polkit.Result.NOT_HANDLED;
    }
    if (!subject.local && subject.isInGroup("wheel")) {
        return polkit.Result.AUTH_ADMIN_KEEP;
    }
});
EOF
chmod a+r /etc/polkit-1/rules.d/50-remote-auth-admin.rules
# Allow password-less login if user has no password (nopasswdlogin group maintained by koi-nopasswdlogin.service)
cp /etc/pam.d/gdm-password /etc/pam.d/gdm-password.old
echo "auth        sufficient    pam_succeed_if.so user ingroup nopasswdlogin" > /etc/pam.d/gdm-password
cat /etc/pam.d/gdm-password.old >> /etc/pam.d/gdm-password
rm /etc/pam.d/gdm-password.old
chmod 644 /etc/pam.d/gdm-password

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
    # In case desktop / dock breaks, add menu items to gnome.
    gnome-extensions enable apps-menu@gnome-shell-extensions.gcampax.github.com
    gnome-extensions enable places-menu@gnome-shell-extensions.gcampax.github.com
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
