#!/bin/bash
# Cleaning up Secureblue.

set -ex -o pipefail

# Pin latest kernel deployment
# ostree admin pin booted
# ostree admin status
# rpm-ostree status
# ostree admin pin pending # You can't.

# Set latest kernel as default and downgraded kernel as rollback
ostree admin status
rpm-ostree status
ostree admin set-default 1
ostree admin status
rpm-ostree status

# Actual cleanup

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
