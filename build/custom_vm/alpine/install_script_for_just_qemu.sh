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

# Set up auto login
mount /dev/vda3 /mnt
cat <<EOF > /mnt/sbin/getty.ttyAMA0
#!/bin/sh
/sbin/getty -n -l /bin/login.ttyAMA0 "\$@"
EOF
cat <<EOF > /mnt/bin/login.ttyAMA0
#!/bin/sh
/bin/login -f root "\$@"
EOF
chmod a+x /mnt/sbin/getty.ttyAMA0
chmod a+x /mnt/bin/login.ttyAMA0

# Shut down
poweroff
