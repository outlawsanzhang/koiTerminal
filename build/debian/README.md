### Prepare the modified Debian image
This guide describes how the provided Debian image is modified from the one from Google.

Download [Google's image](https://dl.google.com/android/ferrochrome/4000000/aarch64/images.tar.gz) and make the following changes: <!-- UPDATE -->
1. Untar `images.tar.gz` to a folder (e.g., `images/`).
2. Replace the content of `images/vm_config.json` with [the version in this repository](build/debian/vm_config.koiterminal.json).
3. Extract `images/cidata.iso` to a folder (e.g., `cidata/`).
4. Replace the content of `cidata/init.sh` with [the version in this repository](build/debian/cloud-init_config/init.sh).
5. Repackage `images/cidata.iso` (see [how they did that](build/debian/build.sh#L295)).
6. Repackage `images.tar.gz`.
```
mkdir images
tar xzf images.tar.gz --directory images/
cat /path/to/vm_config.koiterminal.json > images/vm_config.json
mount images/cidata.iso /mnt
cp -r /mnt cidata
cat /path/to/init.sh > cidata/init.sh
umount /mnt
genisoimage -output images/cidata.iso -V cidata -J -R cidata/
tar czf images.tar.gz --directory images .
```

These changes disable the virtio folder sharing that do not work, and share the ttyd CA as a tiny disk partition instead.
