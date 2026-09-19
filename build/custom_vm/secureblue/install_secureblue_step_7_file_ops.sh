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
    rsync -aP ../cidata/root_files/etc/systemd/system/ "$SYSTEM"
    mkdir -p "$SYSTEM/network.target.wants/"
    ln -s /etc/systemd/system/koi-tun2proxy.path "$SYSTEM/network.target.wants/"
    ln -s /etc/systemd/system/koi-vm-manager.service "$SYSTEM/network.target.wants/"
done
# Clean up
umount nbd0-sys0
qemu-nbd --disconnect /dev/nbd0
