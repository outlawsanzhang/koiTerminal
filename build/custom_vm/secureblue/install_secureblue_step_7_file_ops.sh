set -ex

# Mount system partition
modprobe nbd max_part=8
qemu-nbd --connect /dev/nbd0 secureblue-sys0.qcow2
mkdir -p nbd0-sys0
sleep 3 # nbd needs a second to come online
mount /dev/nbd0p3 nbd0-sys0

# For each deployment...
for SYSTEM in nbd0-sys0/root/ostree/deploy/fedora/deploy/*/etc/systemd/system; do
    # Install and enable services
    chmod -R 755 ../cidata/root_files/etc/systemd/system/
    rsync -aP '--usermap=*:root' '--groupmap=*:root' ../cidata/root_files/etc/systemd/system/ "$SYSTEM"
    mkdir -p "$SYSTEM/network.target.wants/" "$SYSTEM/multi-user.target.wants/"
    ln -s /etc/systemd/system/koi-tun2proxy.path "$SYSTEM/network.target.wants/"
    ln -s /etc/systemd/system/koi-vm-manager.service "$SYSTEM/multi-user.target.wants/"
    ln -s /etc/systemd/system/koi-nopasswdlogin.service "$SYSTEM/multi-user.target.wants/"
    ln -s /etc/systemd/system/koi-grdctl-creds.path "$SYSTEM/multi-user.target.wants/"
    # Set up koiTerminal configs
    CONF="$SYSTEM/../../koi.conf.d"
    mkdir -p "$CONF"
    echo "3389" > "$CONF/rdp-port.marker"
    chmod -R 644 "$CONF"
    chmod -R a+X "$CONF" # +x on folders only
done

# Clean up
umount nbd0-sys0
qemu-nbd --disconnect /dev/nbd0
