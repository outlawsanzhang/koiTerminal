#!/bin/bash

set -ex

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
    while getopts "W:s:" option; do
        case ${option} in
            W)
                workdir="${OPTARG%/}"
                ;;
            s)
                startfrom="${OPTARG}"
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

# For u-boot.bin, see build/custom_vm/alpine/README.md
# For linux_vm_manager, see guest/linux_vm_manager/README.md
# For tun2proxy-bin, see https://github.com/tun2proxy/tun2proxy (build with `--target aarch64-unknown-linux-gnu --release`)
TAR_REQUIREMENTS="build_id vm_config.json u-boot.bin cidata.build_id"
BUILD_REQUIREMENTS="../cidata/root_files/usr/local/bin/linux_vm_manager ../cidata/root_files/usr/local/bin/tun2proxy-bin ../cidata/root_files/etc/systemd/system/"
if ! ls -d $(echo $TAR_REQUIREMENTS $BUILD_REQUIREMENTS); then
    echo '
        Please find the following files/folders:
            '$TAR_REQUIREMENTS $BUILD_REQUIREMENTS'
        Then run me again.
    '
    exit 1
fi

output=images.tar.gz
workdir="${SCRIPT_DIR}"
startfrom=0

parse_options "$@"

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
        -drive "if=virtio,file=secureblue-var0.qcow2,cache=unsafe,discard=unmap,id=hd1" \
        -drive "if=virtio,file=secureblue-hom0.qcow2,cache=unsafe,discard=unmap,id=hd2" \
        ${VDD_ARGS:+-drive} ${VDD_ARGS:+"${VDD_ARGS}"} \
        ${VDE_ARGS:+-drive} ${VDE_ARGS:+"${VDE_ARGS}"} \
        -nic user
    exit_code="$?"
    stty intr ^C # change back interrupt to Ctrl-C
    return "$exit_code"
    # Note: GUI can be obtained by replacing `-nographic` with:
    #     -device virtio-gpu \
    #     -device qemu-xhci \
    #     -device usb-kbd \
    #     -device usb-tablet \
}

execute_step() {
    set +x
    local step="$1"
    local fn="$2"
    local checkpoint="$3"
    if [ $startfrom -lt $((step + 1)) ]; then 
        echo
        echo "================================================================================"
        echo "$DESCRIPTION"
        date
        echo "================================================================================"
        echo
        set -x
        "$fn"
        if [ ! -z "$checkpoint" ]; then
            qemu-img snapshot -c $checkpoint secureblue-sys0.qcow2
            qemu-img snapshot -c $checkpoint secureblue-var0.qcow2
            qemu-img snapshot -c $checkpoint secureblue-hom0.qcow2
        fi
    elif [ $startfrom -le $((step + 1)) ]; then 
        set -x
        if [ ! -z "$checkpoint" ]; then
            qemu-img snapshot -a $checkpoint secureblue-sys0.qcow2
            qemu-img snapshot -a $checkpoint secureblue-var0.qcow2
            qemu-img snapshot -a $checkpoint secureblue-hom0.qcow2
        fi
    fi
    set +x
}

set +x

# ==============================================================================
DESCRIPTION="Step 0: Preparations"
step_0() {
    mkdir -p "${workdir}"
    cd "${workdir}"

    # Install dependencies
    apt install -y wget genisoimage isomd5sum libguestfs-tools

    # Download and set up autoinstall for Fedora Silverblue
    rm Kickstart-Fedora-*.iso || true
    python3 "${SCRIPT_DIR}/kickstart_generator.py"
}
execute_step 0 step_0 ""
KICKSTART_FEDORA_ISO="$(ls Kickstart-Fedora-*.iso)"

# ==============================================================================
DESCRIPTION="Boot 1: Install Fedora Silverblue in QEMU by using Kickstart.
    This takes hours. Good luck."
step_1() {
    qemu-img create -f qcow2 secureblue-sys0.qcow2 20G
    qemu-img create -f qcow2 secureblue-var0.qcow2 10G
    qemu-img create -f qcow2 secureblue-hom0.qcow2 10G
    cp /usr/share/AAVMF/AAVMF_{CODE,VARS}.fd ./
    # UEFI_RO=",readonly=on" \
    VDD_ARGS="if=virtio,file=$KICKSTART_FEDORA_ISO,media=cdrom,cache=unsafe,readonly=on,id=cc" \
        boot_qemu
}
execute_step 1 step_1 1_install

# ==============================================================================
DESCRIPTION="Boot 2: Silverblue from disk. Rebase into Secureblue in QEMU
    by running /home/droid/run_install_secureblue.sh in .bash_profile."
step_2() {
    VDD_ARGS="if=virtio,file=install_secureblue_step_2_switch.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd3" \
        boot_qemu
}
execute_step 2 step_2 2_switch

# ==============================================================================
# # Boot 3 skipped (subsumed into boot 2)
# echo
# date
# echo "Boot 3: Secureblue from disk. Configure Secureblue in QEMU by running" \
#      "/home/droid/run_install_secureblue.sh in .bash_profile."
# echo
# VDD_ARGS="if=virtio,file=install_secureblue_step_3_config.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd3" \
#     boot_qemu
# qemu-img snapshot -c 3_config secureblue-sys0.qcow2
# qemu-img snapshot -c 3_config secureblue-var0.qcow2
# qemu-img snapshot -c 3_config secureblue-hom0.qcow2
DESCRIPTION="Boot 3 skipped (subsumed into boot 2)"
execute_step 3 echo 2_switch # reuses checkpoint

# ==============================================================================
DESCRIPTION="Boot 4: Secureblue from disk. Install packages and set Secureblue
    kernel arguments in QEMU by running /home/droid/run_install_secureblue.sh
    in .bash_profile."
step_4() {
    VDD_ARGS="if=virtio,file=install_secureblue_step_4_kargs.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd3" \
        boot_qemu
}
execute_step 4 step_4 4_kargs

# ==============================================================================
DESCRIPTION="Boot 5: Secureblue from disk. Install alt kernel in QEMU by running
    /home/droid/run_install_secureblue.sh in .bash_profile.
    This takes hours. Good luck."
step_5() {
    VDD_ARGS="if=virtio,file=install_secureblue_step_5_check_kernel.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd3" \
        boot_qemu
}
execute_step 5 step_5 5_check_kernel

# ==============================================================================
DESCRIPTION="Boot 6: Secureblue from disk. Clean up in QEMU by running
    /home/droid/run_install_secureblue.sh in .bash_profile."
step_6() {
    VDD_ARGS="if=virtio,file=install_secureblue_step_6_cleanup.sh,format=raw,cache=unsafe,discard=unmap,readonly=on,id=hd3" \
        boot_qemu
}
execute_step 6 step_6 6_cleanup

# ==============================================================================
DESCRIPTION="Step 7: Direct filesystem manipulations."
step_7() {
    bash install_secureblue_step_7_file_ops.sh
}
execute_step 7 step_7 7_files

# ==============================================================================
DESCRIPTION="Step 8: Sparsify and expand disks."
step_8() {
    qemu-img create -f qcow2 secureblue-system.qcow2 80G
    qemu-img create -f qcow2 secureblue-var.qcow2 1024G
    qemu-img create -f qcow2 secureblue-home.qcow2 1024G
    qemu-img create -f qcow2 secureblue-empty.qcow2 1024G # This is for the user's convenience
    mkdir -p sparse
    TMPDIR="`pwd`/sparse" virt-sparsify secureblue-sys0.qcow2 sparse/secureblue-system.qcow2
    virt-resize --expand /dev/vda3 sparse/secureblue-system.qcow2 secureblue-system.qcow2
    rm sparse/secureblue-system.qcow2
    TMPDIR="`pwd`/sparse" virt-sparsify secureblue-var0.qcow2 sparse/secureblue-var.qcow2
    virt-resize --expand /dev/vda1 sparse/secureblue-var.qcow2 secureblue-var.qcow2
    rm sparse/secureblue-var.qcow2
    TMPDIR="`pwd`/sparse" virt-sparsify secureblue-hom0.qcow2 sparse/secureblue-home.qcow2
    virt-resize --expand /dev/vda1 sparse/secureblue-home.qcow2 secureblue-home.qcow2
    rm sparse/secureblue-home.qcow2

    ls -l secureblue*.qcow2 sparse
}
execute_step 8 step_8 7_files # reuses checkpoint

# ==============================================================================
DESCRIPTION="Step 9: Build cidata.iso."
step_9() {
    chmod a+rx ../cidata/root_files/usr/local/bin/{linux_vm_manager,tun2proxy-bin}
    genisoimage -output cidata.iso -V cidata -J -R ../cidata/

    # pack up image
    IMAGE_CONTENT="build_id vm_config.json cidata.iso cidata.build_id u-boot.bin secureblue-empty.qcow2 secureblue-home.qcow2 secureblue-var.qcow2 secureblue-system.qcow2"
    if ls $(echo $IMAGE_CONTENT); then
        tar czf "${output}" $IMAGE_CONTENT
        # gzip -9 images.tar
        ls -hl "${output}"
    else
        echo '
            Please find the following files:
                '$TAR_REQUIREMENTS'
            Then run `tar czf "'${output}'" '$IMAGE_CONTENT'`.
        '
    fi
}
execute_step 9 step_9 ""

# ==============================================================================
DESCRIPTION="Done."
execute_step 99 echo ""

set -x
