#!/bin/bash
#
# ==============================================================================
# Ferrochrome Rootfs Chroot Utility
# ==============================================================================
#
# This utility mounts a root filesystem image and enters a chroot environment
# with optional extra directory bindings and command execution support.
#
# Key Features:
# 1. Mounts the root partition to a temporary workspace.
# 2. Binds essential system directories (/dev, /proc, /sys).
# 3. Supports custom directory bindings via the -b option.
# 4. Automatically handles DNS configuration via /etc/resolv.conf.
# 5. Ensures clean unmounting on exit or error.
#
# Usage: sudo ./chroot_rootfs.sh [OPTIONS] ${rootfs_path}
# ==============================================================================

set -eo pipefail

### --- Configuration & State --- ###

CHROOT_MOUNT_ARGS=()
CHROOT_WORKSPACE=$(mktemp -d)
CHROOT_COMMAND=""
ROOTFS_IMAGE_PATH=""

### --- Utilities --- ###

# Log informational messages in green
log() { echo -e "\033[1;32m[INFO]\033[0m $1"; }

# Log error messages in red and exit
error() { echo -e "\033[1;31m[ERROR]\033[0m $1" >&2; exit 1; }

check_sudo() {
  if [ "$EUID" -ne 0 ]; then
    error "This script must be run as root (sudo)."
  fi
}

show_help() {
  echo "Usage: sudo $0 [OPTION] ${rootfs_path}"
  echo "Mount rootfs and chroot into it."
  echo ""
  echo "Options:"
  echo "-b SRC:DST   Bind extra directory (can be repeated)"
  echo "-c COMMAND   Command to invoke via /bin/bash -c"
  echo "-h           Print usage and this help message"
}

### --- Functions --- ###

# Parse command line options
parse_options() {
  while getopts "b:c:h" option; do
    case ${option} in
      b) CHROOT_MOUNT_ARGS+=("${OPTARG}") ;;
      c) CHROOT_COMMAND="${OPTARG}" ;;
      h) show_help ; exit ;;
      *) error "Invalid option: $OPTARG" ;;
    esac
  done

  shift $((OPTIND - 1))
  if [[ "$#" -ne 1 ]]; then
    show_help
    error "Rootfs path is required."
  fi
  ROOTFS_IMAGE_PATH="${1}"
}

# Mount the rootfs and setup the chroot environment
mount_environment() {
  log "Mounting rootfs to ${CHROOT_WORKSPACE}..."
  mount "${ROOTFS_IMAGE_PATH}" "${CHROOT_WORKSPACE}"

  # Process extra directory bindings
  for arg in "${CHROOT_MOUNT_ARGS[@]}"; do
    local src=${arg%:*}
    local dst=${arg#*:}
    [[ -z "${src}" || -z "${dst}" ]] && error "Invalid mount binding: ${arg}"

    mkdir -p "${CHROOT_WORKSPACE}/${dst}"
    mount --bind "${src}" "${CHROOT_WORKSPACE}/${dst}"
  done

  # Bind essential system virtual filesystems
  mount --rbind /dev "${CHROOT_WORKSPACE}/dev"
  mount --rbind /proc "${CHROOT_WORKSPACE}/proc"
  mount --rbind /sys "${CHROOT_WORKSPACE}/sys"

  # Configure DNS inside chroot via bind mount (temporary, not baked into image)
  local target_resolv="${CHROOT_WORKSPACE}/etc/resolv.conf"
  if [[ -e "${target_resolv}" || -L "${target_resolv}" ]]; then
    mv "${target_resolv}" "${target_resolv}.bak"
  fi
  touch "${target_resolv}"
  mount --bind /etc/resolv.conf "${target_resolv}"
}

# Enter the chroot environment and execute command if provided
enter_chroot() {
  if [[ -n "${CHROOT_COMMAND}" ]]; then
    log "Executing command inside chroot: ${CHROOT_COMMAND}"
    chroot "${CHROOT_WORKSPACE}" /bin/bash -c "${CHROOT_COMMAND}"
  else
    log "Entering interactive chroot session..."
    chroot "${CHROOT_WORKSPACE}"
  fi
}

# Perform cleanup: unmount all directories and restore configurations

cleanup() {
  log "Cleaning up chroot environment..."

  # Restore original DNS configuration
  local target_resolv="${CHROOT_WORKSPACE}/etc/resolv.conf"
  if mountpoint -q "${target_resolv}"; then
    umount "${target_resolv}"
  fi
  if [[ -e "${target_resolv}.bak" || -L "${target_resolv}.bak" ]]; then
    mv -f "${target_resolv}.bak" "${target_resolv}"
  else
    rm -f "${target_resolv}"
  fi

  # Unmount extra bindings in reverse order
  for (( i=${#CHROOT_MOUNT_ARGS[@]}-1; i>=0; i-- )); do
    local dst=${CHROOT_MOUNT_ARGS[$i]#*:}
    umount "${CHROOT_WORKSPACE}/${dst}" || true
  done

  # Unmount virtual and root filesystems
  umount -R -l "${CHROOT_WORKSPACE}" || true # -l fixes breakage when folder being busy

  # Remove temporary workspace directory
  rm -rf "${CHROOT_WORKSPACE}"
}

### --- Main Execution --- ###

check_sudo
parse_options "$@"

# Setup cleanup trap to ensure unmounting happens on any exit
trap cleanup EXIT

mount_environment
enter_chroot
