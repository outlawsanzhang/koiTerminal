#!/bin/bash

set -x

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

show_help() {
    echo "Usage: sudo $0 [OPTION]... [FILE]"
    echo "Builds a Secureblue image and save it to FILE. [sudo is required]"
    echo "Options:"
    echo "-W WORK_DIR  Specify work dir."
}

check_sudo() {
    if [ "$EUID" -ne 0 ]; then
        echo "Please run as root." ; exit 1
    fi
}

parse_options() {
    while getopts "W:" option; do
        case ${option} in
            W)
                workdir="${OPTARG%/}"
                ;;
            *)
                echo "Invalid option: $OPTARG" ; exit 1
                ;;
        esac
    done
    if [[ "${*:$OPTIND:1}" ]]; then
        output="${*:$OPTIND:1}"
    fi
}


check_sudo
output=images.tar.gz
workdir="${SCRIPT_DIR}"

parse_options "$@"

mkdir -p "${workdir}"
cd "${workdir}"

# Install dependencies
apt install -y wget genisoimage isomd5sum libguestfs-tools

# Potentially: download secureblue image using podman. This way you can actually verify secureblue signature instead of relying on GitHub.
# Note: this modifies container policies on the running machine. Two signature files also need to be added to /etc/pki/containers.
# mkdir -p /etc/containers/registries.d
# mkdir -p /etc/pki/containers
# apt install -y podman
# podman image trust set -t reject ghcr.io/secureblue
# mv /etc/containers/policy.json /etc/containers/policy.json.orig
# jq '.transports.docker."ghcr.io/secureblue" = [{type: "sigstoreSigned", keyPaths: ["/etc/pki/containers/secureblue.pub", "/etc/pki/containers/secureblue-2025.pub"], signedIdentity: {type: "matchRepository"}}]' /etc/containers/policy.json.orig > /etc/containers/policy.json
# echo -e "docker:\n  ghcr.io/secureblue:\n    use-sigstore-attachments: true" > /etc/containers/registries.d/secureblue.yaml
# podman pull ghcr.io/secureblue/silverblue-main-hardened:latest --arch arm64
# podman save ghcr.io/secureblue/silverblue-main-hardened:latest --format oci-archive -o silverblue-main-hardened.tar
# mkdir extract/silverblue-main-hardened
# tar -C extract/silverblue-main-hardened xf silverblue-main-hardened.tar

# Download and set up autoinstall for Fedora Silverblue
rm Kickstart-Fedora-*.iso || true
python3 "${SCRIPT_DIR}/kickstart_generator.py"
KICKSTART_FEDORA_ISO="$(ls Kickstart-Fedora-*.iso)"

# Run the installation in QEMU
boot_qemu() {
    stty intr '^]' # Ctrl-] for interrupting QEMU, so Ctrl-C goes to VM
    trap "stty intr ^C" SIGINT SIGTERM SIGTSTP EXIT
    qemu-system-aarch64 \
        -machine virt \
        -accel tcg,thread=multi \
        -cpu cortex-a57 \
        -smp 4 \
        -m 4g \
        -nographic \
        -drive "if=pflash,file=AAVMF_CODE.fd,format=raw${UEFI_RO}" \
        -drive "if=pflash,file=AAVMF_VARS.fd,format=raw${UEFI_RO}" \
        -drive "if=virtio,file=secureblue-sys0.qcow2,cache=unsafe,discard=unmap,id=hd0" \
        -drive "if=virtio,file=secureblue-usr0.qcow2,cache=unsafe,discard=unmap,id=hd1" \
        ${VDC_ARGS:+-drive} ${VDC_ARGS:+"${VDC_ARGS}"} \
        ${VDD_ARGS:+-drive} ${VDD_ARGS:+"${VDD_ARGS}"} \
        -nic user; stty intr ^C # change back interrupt to Ctrl-C
    # Note: GUI can be obtained by replacing `-nographic` with:
    #     -device virtio-gpu \
    #     -device qemu-xhci \
    #     -device usb-kbd \
    #     -device usb-tablet \
}

# Boot 1
echo
echo "Install Fedora Silverblue in QEMU by using Kickstart. This takes hours. Good luck."
echo
qemu-img create -f qcow2 secureblue-sys0.qcow2 20G
qemu-img create -f qcow2 secureblue-usr0.qcow2 10G
cp /usr/share/AAVMF/AAVMF_{CODE,VARS}.fd ./
# UEFI_RO=",readonly=on" \
VDC_ARGS="if=virtio,file=$KICKSTART_FEDORA_ISO,media=cdrom,cache=unsafe,readonly=on,id=cc" \
    boot_qemu
qemu-img snapshot -c 1_install secureblue-sys0.qcow2
qemu-img snapshot -c 1_install secureblue-usr0.qcow2

# Boot 2
echo
echo "First boot (Silverblue) from disk to rebase into Secureblue in QEMU by running" \
     "/home/droid/run_install_secureblue.sh in .bash_profile."
echo
VDC_ARGS="if=virtio,file=install_secureblue_step_2_switch.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
    boot_qemu
qemu-img snapshot -c 2_switch secureblue-sys0.qcow2
qemu-img snapshot -c 2_switch secureblue-usr0.qcow2

# Boot 3
echo
echo "Second boot (Secureblue) from disk to configure Secureblue in QEMU by running" \
     "/home/droid/run_install_secureblue.sh in .bash_profile."
echo
VDC_ARGS="if=virtio,file=install_secureblue_step_3_config.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
    boot_qemu
qemu-img snapshot -c 3_config secureblue-sys0.qcow2
qemu-img snapshot -c 3_config secureblue-usr0.qcow2

# Boot 4
echo
echo "Third boot (Secureblue) from disk to set Secureblue kernel arguments in QEMU by running" \
     "/home/droid/run_install_secureblue.sh in .bash_profile."
echo
VDC_ARGS="if=virtio,file=install_secureblue_step_4_kargs.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
    boot_qemu
qemu-img snapshot -c 4_kargs secureblue-sys0.qcow2
qemu-img snapshot -c 4_kargs secureblue-usr0.qcow2

# Boot 5
echo
echo "Fourth boot (Secureblue) from disk to install alt kernel in QEMU by running" \
     "/home/droid/run_install_secureblue.sh in .bash_profile. This takes hours. Good luck."
echo
VDC_ARGS="if=virtio,file=install_secureblue_step_5_check_kernel.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
    boot_qemu
qemu-img snapshot -c 5_check_kernel secureblue-sys0.qcow2
qemu-img snapshot -c 5_check_kernel secureblue-usr0.qcow2

# Boot 6
echo
echo "Fifth boot (Secureblue) from disk to clean up in QEMU by running" \
     "/home/droid/run_install_secureblue.sh in .bash_profile."
echo
VDC_ARGS="if=virtio,file=install_secureblue_step_6_cleanup.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
    boot_qemu
qemu-img snapshot -c 6_cleanup secureblue-sys0.qcow2
qemu-img snapshot -c 6_cleanup secureblue-usr0.qcow2

# Sparsify and expand disks
qemu-img create -f qcow2 secureblue-system.qcow2 80G
qemu-img create -f qcow2 secureblue-user.qcow2 1024G
mkdir -p sparse
TMPDIR="`pwd`/sparse" virt-sparsify secureblue-sys0.qcow2 sparse/secureblue-system.qcow2
virt-resize --expand /dev/vda3 sparse/secureblue-system.qcow2 secureblue-system.qcow2
rm sparse/secureblue-system.qcow2
TMPDIR="`pwd`/sparse" virt-sparsify secureblue-usr0.qcow2 sparse/secureblue-user.qcow2
virt-resize --expand /dev/vda1 sparse/secureblue-user.qcow2 secureblue-user.qcow2
rm sparse/secureblue-user.qcow2

ls -l secureblue*.qcow2 sparse

touch root_part
if [ -e build_id ] && [ -e vm_config.json ] && [ -e u-boot.bin ]; then
    tar czf images.tar.gz root_part build_id vm_config.json u-boot.bin secureblue-user.qcow2 secureblue-system.qcow2
    # gzip -9 images.tar
    ls -l "${output}"
else
    echo '
        Please find the following files:
        - build_id
        - vm_config.json
        - u-boot.bin
        Then run `tar czf "'${output}'" root_part build_id vm_config.json u-boot.bin secureblue-user.qcow2 secureblue-system.qcow2`.
    '
fi
