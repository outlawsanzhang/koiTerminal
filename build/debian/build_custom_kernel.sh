#!/bin/bash

set -x

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"

show_help() {
	echo "Usage: sudo $0 [OPTION]... [FILE]"
	echo "Builds debian packages for our custom kernel. [sudo is required]"
	echo "Options:"
	echo "-a ARCH      Architecture of the image [default is host arch: $(uname -m)]"
	echo "-d DEST_DIR  Destination directory for packages [default: $SCRIPT_DIR]"
	echo "-h           Print usage and this help message and exit."
	echo "-w           Save temp work directory [for debugging]"
	echo "-W WORK_DIR  Specify work dir instead of temporarily creating. Imply -w [for debugging]"
}

check_sudo() {
	if [ "$EUID" -ne 0 ]; then
		echo "Please run as root." ; exit 1
	fi
}

parse_options() {
	while getopts "a:d:hwW:" option; do
		case ${option} in
			a)
				arch="$OPTARG"
				;;
			d)
				dest_dir="$OPTARG"
				;;
			h)
				show_help ; exit
				;;
			w)
				save_workdir=1
				;;
			W)
				workdir="${OPTARG%/}"
				save_workdir=1
				may_skip_build=1
				;;
			*)
				echo "Invalid option: $OPTARG" ; exit 1
				;;
		esac
	done
	case "$arch" in
		aarch64)
			debian_arch="arm64"
			;;
		x86_64)
			debian_arch="amd64"
			;;
		*)
			echo "Invalid architecture: $arch" ; exit 1
			;;
	esac
	if [[ "${*:$OPTIND:1}" ]]; then
		output="${*:$OPTIND:1}"
	fi
}

build_custom_kernel() {
	if [[ "$may_skip_build" == 1 && -f "${dest_dir}/vmlinuz" ]]; then
		echo "Skipping build_custom_kernel(). ${dest_dir}/vmlinuz already exists"
		return
	fi

	local deb_base_url="https://deb.debian.org/debian"
	local deb_security_base_url="https://security.debian.org/debian-security"

	local pool_dir="pool/main/l/linux"
	local ksrc_base_url="${deb_base_url}/${pool_dir}"
	local ksrc_security_base_url="${deb_security_base_url}/${pool_dir}"

	# NOTE: 6.1 is the latest LTS kernel for which Debian's kernel build scripts
	#       work on Python 3.10, the default version on our Ubuntu 22.04 builders.
	#
	#       We track the latest Debian stable kernel version for the 6.1 branch,
	#       which can be found at:
	#       https://packages.debian.org/stable/linux-source-6.1
	local debian_kver="6.1.135-1"

	local dsc_file="linux_${debian_kver}.dsc"
	local orig_ksrc_file="linux_${debian_kver%-*}.orig.tar.xz"
	local debian_ksrc_file="linux_${debian_kver}.debian.tar.xz"

	# 0. Grab the kernel sources, and the latest debian keyrings
	mkdir -p "${workdir}/kernel"
	pushd "${workdir}/kernel" > /dev/null

	ls "${dsc_file}" || wget "${ksrc_security_base_url}/${dsc_file}" || \
	wget "${ksrc_base_url}/${dsc_file}"

	ls "${orig_ksrc_file}" || wget "${ksrc_security_base_url}/${orig_ksrc_file}" || \
	wget "${ksrc_base_url}/${orig_ksrc_file}"

	ls "${debian_ksrc_file}" || wget "${ksrc_security_base_url}/${debian_ksrc_file}" || \
	wget "${ksrc_base_url}/${debian_ksrc_file}"

	rsync -az --progress keyring.debian.org::keyrings/keyrings/ /usr/share/keyrings/

	# 1. Verify, extract and merge patches into the original kernel sources
	ls "linux-${debian_kver%-*}" || dpkg-source --require-strong-checksums \
	            --require-valid-signature \
	            --extract "${dsc_file}"
	pushd "linux-${debian_kver%-*}" > /dev/null

	local kpatches_src="$SCRIPT_DIR/kernel/patches"
	cp -r "${kpatches_src}/avf" debian/patches/
	cat "${kpatches_src}/series" >> debian/patches/series
	patch -p0 < "$SCRIPT_DIR/debian-bugfix.patch"
	./debian/rules orig

	local custom_flavour="avf"
	local debarch_flavour="${custom_flavour}-${debian_arch}"

	local abi_kver="$(sed -nE 's;Package: linux-support-(.*);\1;p' debian/control)"
	local abi_common="${abi_kver}-common"
	abi_flavour="${abi_kver}-${debarch_flavour}"

	# 2. Define our custom flavour and regenerate control file
	# NOTE: Our flavour extends Debian's `cloud` config on the `none` featureset.
	cp "$SCRIPT_DIR/kernel/config" \
	   debian/config/${debian_arch}/config.${debarch_flavour}

	sed -z "s;\[base\]\nflavours:;[base]\nflavours:\n ${debarch_flavour};" \
	    -i debian/config/${debian_arch}/none/defines
	cat >> debian/config/${debian_arch}/none/defines <<EOF
[${debarch_flavour}_image]
configs:
 config.cloud
 ${debian_arch}/config.${debarch_flavour}
EOF
	cat >> debian/config/${debian_arch}/defines <<EOF
[${debarch_flavour}_description]
hardware: ${arch} AVF
hardware-long: ${arch} Android Virtualization Framework
EOF
	./debian/rules debian/control || true

	# 3. Build the kernel and generate Debian packages
	if (($(df -B1 /tmp | awk 'NR==1 { for (i = 1; i <= NF; i++) if ($i == "1B-blocks") cidx=i } NR > 1 { print $cidx }') < 2*1024*1024*1024)); then
		# Packaging .deb uses /tmp
		mount -o remount,size=2G /tmp
	fi
	./debian/rules source
	export DEB_BUILD_PROFILES="nodoc"
	[[ "$arch" == "$(uname -m)" ]] || export $(dpkg-architecture -a $debian_arch)
	make -j$(nproc) -f debian/rules.gen \
	     "binary-indep" \
	     "binary-arch_${debian_arch}_none_${debarch_flavour}"

	popd > /dev/null

	# 4. Create the kernel_extras disk image.
	mkdir kernel_extras
	dpkg-deb --extract "linux-image-${abi_flavour}-unsigned_${debian_kver}_${debian_arch}.deb" \
	                   kernel_extras
	dpkg-deb --extract "linux-headers-${abi_flavour}_${debian_kver}_${debian_arch}.deb" \
	                   kernel_extras
	dpkg-deb --extract "linux-headers-${abi_common}_${debian_kver}_all.deb" \
	                   kernel_extras
	depmod -b kernel_extras $abi_flavour

	mv kernel_extras/boot/vmlinuz* vmlinuz
	if [[ "$arch" == "aarch64" ]]; then
		lz4 -BD -12 -q vmlinuz vmlinuz.lz4
		mv vmlinuz.lz4 vmlinuz
	fi
	mv vmlinuz "${dest_dir}/vmlinuz"

	rm -rf kernel_extras/{boot,usr/share}

	mkfs.erofs kernel_extras_part kernel_extras
	kernel_extras_loopdev="$(losetup -f --show kernel_extras_part)"
	kernel_extras_guid="$(blkid -s UUID -o value "${kernel_extras_loopdev}")"
	losetup -d "${kernel_extras_loopdev}"
	mv kernel_extras_part "${dest_dir}/kernel_extras_part"
	mv kernel_extras "${dest_dir}/kernel_extras"
}

build_initrd() {
	if [[ "$may_skip_build" == 1 && -f "${dest_dir}/initrd.img" ]]; then
		echo "Skipping build_initrd(). ${workdir}/initrd.img already exists"
		return
	fi

	mkdir -p "${workdir}/initrd"
	pushd "${workdir}/initrd" > /dev/null

	local initrd_modules
	mapfile -t initrd_modules < <(grep -vE '^\s*#|^\s*$' "${SCRIPT_DIR}/initrd/modules")

	local modules_src="$(realpath "${dest_dir}/kernel_extras/lib/modules/${abi_flavour}")"
	for modname in "${initrd_modules[@]}" ; do
		modprobe --dirname "${dest_dir}/kernel_extras" \
		         --set-version "$abi_flavour" \
		         --show-depends \
		         "$modname" | awk '/insmod/ {print $2}'
	done | sort -u | sed "s;^${modules_src}\/;;" > modules.list

	mkdir -p archive/{bin,lib,sbin}
	cp -arv "${SCRIPT_DIR}/initrd/scripts" archive/

	local busybox_base_url="https://busybox.net/downloads/"
	local busybox_version="1.37.0"

	wget "${busybox_base_url}/busybox-${busybox_version}.tar.bz2"
	wget "${busybox_base_url}/busybox-${busybox_version}.tar.bz2.sha256"
	sha256sum --check busybox-${busybox_version}.tar.bz2.sha256
	tar -xf busybox-${busybox_version}.tar.bz2

	pushd "busybox-${busybox_version}" > /dev/null
	if [[ "$arch" != "$(uname -m)" ]]; then
		export ARCH="${arch}"
		export CROSS_COMPILE="${arch}-linux-gnu-"
	fi
	make distclean
	make defconfig
	# NOTE: Overrides for busybox default configs must be PREPENDED.
	mv .config .config.orig
	cat "${SCRIPT_DIR}/initrd/busybox/config" > .config
	cat .config.orig >> .config
	make oldconfig
	make -j$(nproc)
	make install CONFIG_PREFIX="${workdir}/initrd/archive"
	popd > /dev/null

	pushd "${workdir}/initrd/archive" > /dev/null
	local modules_dest="lib/modules/${abi_flavour}"
	mkdir -p "${modules_dest}"
	pushd "${modules_dest}" > /dev/null
	while read -r modpath ; do
		mkdir -p "$(dirname "$modpath")"
		cp -av "${modules_src}/${modpath}" "$modpath"
	done < "${workdir}/initrd/modules.list"
	popd > /dev/null
	depmod -b . $abi_flavour

	echo "KERNEL_EXTRAS_UUID=${kernel_extras_guid}" >> scripts/env-setup
	cat > sbin/early_load_modules <<EOF
#!/bin/sh
set -e

. /scripts/env-setup
. /scripts/helper-utils

EOF
	for mod in "${initrd_modules[@]}" ; do
		echo "modprobe $mod || __error 'Failed to load $mod'" >> sbin/early_load_modules
	done
	cp "${SCRIPT_DIR}/initrd/init" init
	chmod +x init sbin/early_load_modules

	find . | cpio --create --format=newc | zstd -19 -f -o "${dest_dir}/initrd.img"

	popd > /dev/null
	popd > /dev/null
}

install_prerequisites() {
	apt update
	packages+=(
		bc
		bison
		build-essential
		ca-certificates
		cpio
		debhelper
		dh-exec
		dh-python
		erofs-utils
		flex
		gcc-12
		initramfs-tools
		kernel-wedge
		libelf-dev
		libpci-dev
		libssl-dev
		lz4
		pahole
		python3-docutils
		python3-jinja2
		quilt
		rsync
		wget
		zstd
	)
	if [[ "$arch" == "aarch64" ]]; then
		packages+=(
			gcc-aarch64-linux-gnu
			gcc-12-aarch64-linux-gnu
			gcc-arm-linux-gnueabihf
			libc6-dev-arm64-cross
		)
	fi
	DEBIAN_FRONTEND=noninteractive \
	apt install --no-install-recommends --assume-yes "${packages[@]}"
}

clean_up() {
	[ "$save_workdir" -eq 1 ] || rm -rf "${workdir}"
}

set -e
trap clean_up EXIT

abi_flavour=
kernel_extras_guid=
save_workdir=0
may_skip_build=0
dest_dir=$SCRIPT_DIR
workdir=

check_sudo
parse_options "$@"

if [[ -n "${workdir}" ]]; then
	mkdir -p "${workdir}" || true
else
	workdir=$(mktemp -d)
fi
echo $workdir

install_prerequisites
build_custom_kernel
build_initrd
