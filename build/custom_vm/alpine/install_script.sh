#!/bin/ash
set -e

# Install Alpine with an answer file
cd /dev/shm
cat <<EOF > ANSWER_FILE
# Use US layout with US variant
# KEYMAPOPTS="us us"
KEYMAPOPTS=none

# Set hostname to 'alpine'
HOSTNAMEOPTS=alpine

# Set device manager to mdev
DEVDOPTS=mdev

# Contents of /etc/network/interfaces
INTERFACESOPTS="auto lo
iface lo inet loopback

auto eth0
iface eth0 inet dhcp
hostname alpine-test
"

# Search domain of example.com, Google public nameserver
# DNSOPTS="-d example.com 8.8.8.8"

# Set timezone to UTC
#TIMEZONEOPTS="UTC"
TIMEZONEOPTS=UTC

# set http/ftp proxy
#PROXYOPTS="http://webproxy:8080"
PROXYOPTS=none

# Add first mirror (CDN)
APKREPOSOPTS="-1"

# Create admin user
# USEROPTS="-a -u -g audio,input,video,netdev juser"
#USERSSHKEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOIiHcbg/7ytfLFHUNLRgEAubFz/13SwXBOM/05GNZe4 juser@example.com"
#USERSSHKEY="https://example.com/juser.keys"

# Install Openssh
SSHDOPTS=openssh
#ROOTSSHKEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOIiHcbg/7ytfLFHUNLRgEAubFz/13SwXBOM/05GNZe4 juser@example.com"
#ROOTSSHKEY="https://example.com/juser.keys"

# Use openntpd
# NTPOPTS="openntpd"
NTPOPTS=none

# Use /dev/vda as a sys disk
DISKOPTS="-m sys /dev/vda"

# Setup storage with label APKOVL for config storage
#LBUOPTS="LABEL=APKOVL"
LBUOPTS=none

#APKCACHEOPTS="/media/LABEL=APKOVL/cache"
APKCACHEOPTS=none
EOF
yes "" | ERASE_DISKS=/dev/vda setup-alpine -e -f ANSWER_FILE

# Modify the tty used to communicate with the VM,
# and set up auto login
mount /dev/vda3 /mnt
sed -i -e 's;^ttyAMA0:\S*;ttyS0::respawn:/sbin/getty.ttyS0;g' -e 's;ttyAMA0;ttyS0;g' /mnt/etc/inittab
cat <<EOF > /mnt/sbin/getty.ttyS0
#!/bin/sh
/sbin/getty -n -l /bin/login.ttyS0 "\$@"
EOF
cat <<EOF > /mnt/bin/login.ttyS0
#!/bin/sh
/bin/login -f root "\$@"
EOF
chmod a+x /mnt/sbin/getty.ttyS0
chmod a+x /mnt/bin/login.ttyS0

# Install basic utilities
source /mnt/etc/os-release
MINOR_VERSION="v${VERSION_ID%.[0-9]*}"
apk add nano vim screen tmux e2fsprogs-extra readline bash --root /mnt
apk add emacs --repository="https://dl-cdn.alpinelinux.org/alpine/$MINOR_VERSION/community" --root /mnt
apk add neofetch --repository=https://dl-cdn.alpinelinux.org/alpine/edge/testing --root /mnt

# Configure splash screen
cat <<EOF > /mnt/root/.profile
neofetch
EOF

# Run resize2fs on root partition at boot to expand it beyond install size
ROOT_UUID=$(blkid | grep vda3 | grep -o 'UUID="[^"]*"' | grep -o -E '[0-9a-f-]{36}')
cat <<EOF > /mnt/etc/local.d/resize2fs.start
#!/bin/sh
ROOT_DEV="\$(blkid | grep "$ROOT_UUID" | cut -d: -f1)"
resize2fs \$ROOT_DEV >/dev/shm/resize2fs.log 2>/dev/shm/resizefs.err
EOF
chmod a+x /mnt/etc/local.d/resize2fs.start
chroot /mnt rc-update add local

# Shut down
poweroff
