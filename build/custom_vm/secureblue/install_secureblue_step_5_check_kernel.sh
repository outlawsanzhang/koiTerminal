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

# Install pkexec wrapper
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
