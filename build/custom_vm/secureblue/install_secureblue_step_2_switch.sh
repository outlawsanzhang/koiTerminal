#!/bin/bash
# This part is from https://github.com/secureblue/secureblue/blob/live/docs/example.butane#L22
# and the license is Apache-2.0: https://github.com/secureblue/secureblue/blob/live/LICENSE
# Doing this rebases onto Secureblue on first login of user droid after install.

set -ex -o pipefail

systemctl disable --now zincati.service 2>/dev/null || true
systemctl stop rpm-ostreed-automatic.timer rpm-ostreed-automatic.service 2>/dev/null || true
ostree config set core.min-free-space-percent 0

# set up container signature verification
cat <<EOF > /etc/containers/policy.json
{
    "default": [
        {
            "type": "reject"
        }
    ],
    "transports": {
        "docker": {
            "ghcr.io/secureblue": [
                {
                    "type": "sigstoreSigned",
                    "keyPath": "/etc/tmp.secureblue.pub",
                    "signedIdentity": {"type": "matchRepository"}
                }
            ]
        }
    }
}
EOF

cat <<EOF > /etc/containers/registries.d/secureblue.yaml
docker:
  ghcr.io/secureblue:
    use-sigstore-attachments: true
EOF

# <!-- UPDATE --> key unchanged?
echo -e '-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEdruKsOhUkgdd0lNDHNBymE2Wyb/p\nGVnx59QbNoGFbImqZLRVt6uQnO9MfiHU9IZiJl9aNetfAqDsgsltAUQnXQ==\n-----END PUBLIC KEY-----' > /etc/tmp.secureblue.pub # Not gpg key; cosign key. Obtained from a x86_64 Secureblue install. They only sign x86_64 ISOs with the key posted on their website.
# You can also get this key from https://github.com/secureblue/secureblue/blob/live/docs/example.butane
# Or, use this to install the x64 version (after verifying the ISO signature) and find it under /etc/pki/containers/
#     qemu-img create -f qcow2 secureblue-sys0.qcow2 20G
#     qemu-system-x86_64 \
#         -accel tcg,thread=multi \
#         -smp 4 \
#         -m 4g \
#         -cpu max \
#         -drive "if=virtio,file=secureblue-sys0.qcow2,cache=unsafe,discard=unmap,id=hd0" \
#         -usb -device usb-tablet \
#         -cdrom secureblue-silverblue-main-hardened-20260502.iso
# If you have kvm, you should probably use kvm instead of tcg which is very slow

# rebase to secureblue
while ! bootc switch --enforce-container-sigpolicy ghcr.io/secureblue/silverblue-main-hardened:latest; do
    sleep 10
done

# remove temporary signature policy
rm /etc/containers/policy.json
rm /etc/containers/registries.d/secureblue.yaml
rm /etc/tmp.secureblue.pub
