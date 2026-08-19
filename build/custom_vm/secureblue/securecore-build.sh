#!/bin/bash
# Work in progress.

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

echo This is a work in progress. Press ENTER to proceed, or press Ctrl+C to abort.
read

mkdir -p "${workdir}"
cd "${workdir}"

# Install dependencies
apt install -y wget podman libguestfs-tools

# Download and verify Fedora CoreOS
gpg2 --batch --keyserver keyserver.ubuntu.com --recv-keys C6E7F081CF80E13146676E88829B606631645531
FEDORA_BUILD=43.20260217.3.1
FEDORA_IMAGE=fedora-coreos-${FEDORA_BUILD}-qemu.aarch64.qcow2.xz
ls "${FEDORA_IMAGE}" || wget "https://builds.coreos.fedoraproject.org/prod/streams/stable/builds/${FEDORA_BUILD}/aarch64/${FEDORA_IMAGE}"
ls "${FEDORA_IMAGE}.sig" || wget "https://builds.coreos.fedoraproject.org/prod/streams/stable/builds/${FEDORA_BUILD}/aarch64/${FEDORA_IMAGE}.sig"
gpg2 --verify "${FEDORA_IMAGE}.sig"

# Butane to ignition
podman run --rm -it quay.io/coreos/butane:release \
    --pretty --strict < butane.md > butane.ign

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
        -drive "if=virtio,file=securecore.qcow2,cache=unsafe,discard=unmap,id=hd0" \
        ${IGNT_ARGS:+-fw_cfg} ${IGNT_ARGS:+"${IGNT_ARGS}"} \
        ${DISK_ARGS:+-drive} ${DISK_ARGS:+"${DISK_ARGS}"} \
        -nic user; stty intr ^C # change back interrupt to Ctrl-C
}

# Boot 1
echo
echo "First boot (CoreOS) from disk to rebase into Securecore in QEMU by running" \
     "/etc/run_install_secureblue.sh in .bash_profile."
echo
qemu-img create -f qcow2 securecore.qcow2 20G
cp /usr/share/AAVMF/AAVMF_{CODE,VARS}.fd ./
IGNT_ARGS="name=opt/com.coreos/config,file=butane.ign" \
    boot_qemu
qemu-img snapshot -c 2_switch secureblue-sys0.qcow2
qemu-img snapshot -c 2_switch secureblue-usr0.qcow2

# # Boot 3
# echo
# echo "Second boot (Secureblue) from disk to configure Secureblue in QEMU by running" \
#      "/etc/run_install_secureblue.sh in .bash_profile."
# echo
# DISK_ARGS="if=virtio,file=install_secureblue_step_3_config.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
#     boot_qemu
# qemu-img snapshot -c 3_config secureblue-sys0.qcow2
# qemu-img snapshot -c 3_config secureblue-usr0.qcow2
# 
# # Boot 4
# echo
# echo "Third boot (Secureblue) from disk to set Secureblue kernel arguments in QEMU by running" \
#      "/etc/run_install_secureblue.sh in .bash_profile."
# echo
# DISK_ARGS="if=virtio,file=install_secureblue_step_4_kargs.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
#     boot_qemu
# qemu-img snapshot -c 4_kargs secureblue-sys0.qcow2
# qemu-img snapshot -c 4_kargs secureblue-usr0.qcow2
# 
# # Boot 5
# echo
# echo "Fourth boot (Secureblue) from disk to clean up in QEMU by running" \
#      "/etc/run_install_secureblue.sh in .bash_profile."
# echo
# DISK_ARGS="if=virtio,file=install_secureblue_step_5_cleanup.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd2" \
#     boot_qemu
# qemu-img snapshot -c 5_cleanup secureblue-sys0.qcow2
# qemu-img snapshot -c 5_cleanup secureblue-usr0.qcow2

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

if [ -e build_id ] && [ -e vm_config.json ] && [ -e u-boot.bin ]; then
    tar cf - build_id vm_config.json u-boot.bin secureblue-user.qcow2 secureblue-system.qcow2 | gzip -9 > "${output}"
    ls -l "${output}"
else
    echo '
        Please find the following files:
        - build_id
        - vm_config.json
        - u-boot.bin
        Then run `tar czf "'${output}'" secureblue-system.qcow2 secureblue-user.qcow2 build_id vm_config.json u-boot.bin`.
    '
fi
