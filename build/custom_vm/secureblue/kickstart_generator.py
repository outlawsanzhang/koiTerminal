#!/usr/bin/env python3
# This script is adapted from: https://github.com/brianlturney/Kickstart-ISO-Generator/
# 
# MIT License
# 
# Copyright (c) 2022 Brian | Turney
# 
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
# 
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
# 
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

# Change the MIRROR_URL and SOURCE_ISO_NAME below. This script will automate the source ISO download, customization, and custom ISO creation
# You can also download your own ISO into the directory of this script and paste the name of that iso into the SOURCE_ISO_NAME line below without changing the URL
MIRROR_URL = "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Silverblue/aarch64/iso/"
SOURCE_ISO_NAME = "Fedora-Silverblue-ostree-aarch64-43-1.6.iso"
CHECKSUM_NAME = "Fedora-Silverblue-43-1.6-aarch64-CHECKSUM"
#MIRROR_URL = "https://download.rockylinux.org/pub/rocky/9/isos/x86_64/"
#SOURCE_ISO_NAME = "Rocky-9.0-x86_64-dvd.iso"
#MIRROR_URL = "http://mirror.stream.centos.org/9-stream/BaseOS/x86_64/iso/"
#SOURCE_ISO_NAME = "CentOS-Stream-9-latest-x86_64-dvd1.iso"
#MIRROR_URL = "https://developers.redhat.com/content-gateway/file/"
#SOURCE_ISO_NAME = "rhel-9.0-x86_64-dvd.iso"

# Import the os module
import os

# Clear the terminal
# cmd = "clear"
# os.system(cmd)

# Assign color codes
WHITE = '\x1b[1;37;40m'
GREEN = '\x1b[1;32;40m'
BLUE = '\x1b[1;36;40m'
ORANGE = '\x1b[1;33;40m'
RED = '\x1b[1;31;40m'
RED_FLASHING = '\x1b[6;31;40m'

# Set the projects working directories
CWD = "./"
KICKSTART_KS_CFG =  "ks.cfg"
ISO_SOURCE_MOUNT = "mount"
ISO_SOURCE_EXTRACT = "extract"
KICKSTART_ISO_NAME = "Kickstart-"

# Determine OS
with open("/etc/os-release") as distro:
    for line in distro:
        if "centos" in line:
            OS_DISTRO = "CentOS"
            ISOPACKAGE="mkisofs"
            break
        elif "fedora" in line:
            OS_DISTRO = "Fedora"
            ISOPACKAGE="mkisofs"
            break           
        elif "debian" in line:
            OS_DISTRO = "Debian"
            ISOPACKAGE="mkisofs"
            break
        elif "kali" in line:
            OS_DISTRO = "Kali"
            ISOPACKAGE="mkisofs"
            break                                 
        else:
            OS_DISTRO = "This OS is not supported"
print(GREEN + "[ Ok ]" + WHITE + " Current Linux Distro: " + BLUE + OS_DISTRO)       

# Check if the directories exist or not
if not os.path.exists(CWD + ISO_SOURCE_MOUNT):   
    # If not present then create it
    print(ORANGE + "[ Creating ]" + WHITE + " Checking " + ISO_SOURCE_MOUNT + " directory")
    os.makedirs(CWD + ISO_SOURCE_MOUNT)
else:
    print(GREEN + "[ Ok ]" + WHITE + " Checking " + ISO_SOURCE_MOUNT + " directory")
if not os.path.exists(CWD + ISO_SOURCE_EXTRACT):   
    # if not present then create it
    print(ORANGE + "[ Creating ]" + WHITE + " Checking " + ISO_SOURCE_EXTRACT + " directory")
    os.makedirs(CWD + ISO_SOURCE_EXTRACT)
else:
    print(GREEN + "[ Ok ]" + WHITE + " Checking " + ISO_SOURCE_EXTRACT + " directory")

# Check if source ISO already exists
if not os.path.exists(CWD + SOURCE_ISO_NAME):   
    # If not present then create it.
    print(RED + "[ Downloading ] " + WHITE + " Checking source iso file. Not found." + ORANGE)
    cmd = "wget -O " + CWD + SOURCE_ISO_NAME + " " + MIRROR_URL + SOURCE_ISO_NAME + "  -q --show-progress"
    os.system(cmd)
else:
    print(GREEN + "[ Ok ]" + WHITE + " Source ISO file already exists.")
# Check if ISO checksum already exists
if not os.path.exists(CWD + CHECKSUM_NAME):   
    # If not present then create it.
    print(RED + "[ Downloading ] " + WHITE + " Checking source checksum file. Not found." + ORANGE)
    cmd = "wget -O " + CWD + CHECKSUM_NAME + " " + MIRROR_URL + CHECKSUM_NAME + "  -q --show-progress"
    os.system(cmd)
else:
    print(GREEN + "[ Ok ]" + WHITE + " Source checksum file already exists.")

# Check ISO signature
cmd = "gpg2 --batch --keyserver keyserver.ubuntu.com --recv-keys C6E7F081CF80E13146676E88829B606631645531" # Fedora (43)
os.system(cmd)
cmd = "gpg2 --batch --keyserver keyserver.ubuntu.com --recv-keys 36F612DCF27F7D1A48A835E4DBFCF71C6D9F90A6" # Fedora (44)
os.system(cmd)
print(GREEN + "[ Ok ]" + GREEN + " Download Completed" + WHITE)
cmd = "gpg2 --verify " + CWD + CHECKSUM_NAME
assert os.system(cmd) == 0, "GPG verification failed."
cmd = "sha256sum -c --ignore-missing " + CWD + CHECKSUM_NAME
assert os.system(cmd) == 0, "SHA256 checksum failed."
print(GREEN + "[ Ok ]" + GREEN + " Download Verified" + WHITE)

# Mount ISO image
print(GREEN + "[ Ok ]" + BLUE + " Mounting source ISO" + WHITE)
cmd = "mount " + CWD + SOURCE_ISO_NAME + " " + CWD + ISO_SOURCE_MOUNT
os.system(cmd)

# Extract ISO
print(GREEN + "[ Ok ]" + BLUE + " Extracting source ISO" + GREEN)
cmd = "rsync -a --info=progress2 --human-readable " + CWD + ISO_SOURCE_MOUNT + "/ " + CWD + ISO_SOURCE_EXTRACT + "/"
os.system(cmd)
print(GREEN + "[ Ok ]" + BLUE + " Extraction Complete" + WHITE)

# Copy kickstart config
print(GREEN + "[ Ok ]" + BLUE + " Copying kickstart config to extracted ISO" + WHITE)
cmd = "cp -a " + CWD + KICKSTART_KS_CFG + " " + CWD + ISO_SOURCE_EXTRACT
assert os.system(cmd) == 0, "Copying kickstart config failed."

# Get the label of the ISO required for the grub.cfg file and the mkisfs command
GRUB_CFG = "EFI/BOOT/grub.cfg"
import subprocess
ISO_LABEL = str(subprocess.check_output("echo | grep -oP 'LABEL=+\K[^ ]+' " + CWD + ISO_SOURCE_EXTRACT + "/" + GRUB_CFG + " | head -1", shell=True))
ISO_LABEL = ISO_LABEL[:-3]
ISO_LABEL = ISO_LABEL[2:]
print(GREEN + "[ Ok ]" + BLUE + " The current ISO label is " + ISO_LABEL + WHITE)

# Boot menu changes
print(GREEN + "[ Ok ]" + BLUE + " Modifying boot parameters" + WHITE)
cmd = "sed -i 's@[Ii]nstall@Kickstart Install@g' " + CWD + ISO_SOURCE_EXTRACT + "/" + GRUB_CFG
os.system(cmd)
cmd = "sed -i 's@timeout=.*@timeout=5@g' " + CWD + ISO_SOURCE_EXTRACT + "/" + GRUB_CFG
os.system(cmd)
cmd = "sed -i 's@vmlinuz@vmlinuz inst.ks=hd:vdc:/ks.cfg@g' " + CWD + ISO_SOURCE_EXTRACT + "/" + GRUB_CFG
os.system(cmd)

# Check if kickstart ISO already exists
if not os.path.exists(CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + ".iso"):
    NOTHING = "do"
else:
    cmd = "rm -f " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + ".iso"
    assert os.system(cmd) == 0, "Remove old kickstart ISO failed"

# Make kickstart ISO
if OS_DISTRO == "Fedora":
    import importlib.util 
    is_present = importlib.util.find_spec(ISOPACKAGE) #find_spec will look for the package
    if is_present is None:
        print(GREEN + "[ Ok ]" + BLUE + " Checking for ISO package handler" + WHITE)
        cmd = "yum install " + ISOPACKAGE + " -y"
        os.system(cmd)
        print(GREEN + "[ Ok ]" + BLUE + " Creating Kickstart ISO" + WHITE)
        cmd = "mkisofs -relaxed-filenames -J -R -o " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + " -eltorito-platform efi -b images/efiboot.img -V '" + ISO_LABEL + "' -no-emul-boot " + CWD + ISO_SOURCE_EXTRACT
        assert os.system(cmd) == 0, "ISO creation failed."
    else:
        print(GREEN + "[ Ok ]" + BLUE + " Creating Kickstart ISO" + WHITE)
        cmd = "mkisofs -relaxed-filenames -J -R -o " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + " -eltorito-platform efi -b images/efiboot.img -V '" + ISO_LABEL + "' -no-emul-boot " + CWD + ISO_SOURCE_EXTRACT
        assert os.system(cmd) == 0, "ISO creation failed."

if OS_DISTRO == "Kali" or OS_DISTRO == "Debian":
    import importlib.util 
    is_present = importlib.util.find_spec(ISOPACKAGE) #find_spec will look for the package
    if is_present is None:
        print(GREEN + "[ Ok ]" + BLUE + " Checking for ISO package handler" + WHITE)
        cmd = "apt install " + ISOPACKAGE + " -y"
        os.system(cmd)
        print(GREEN + "[ Ok ]" + BLUE + " Creating Kickstart ISO" + WHITE)
        cmd = "genisoimage -relaxed-filenames -J -R -o " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + " -e images/efiboot.img -V '" + ISO_LABEL + "' -no-emul-boot " + CWD + ISO_SOURCE_EXTRACT
        assert os.system(cmd) == 0, "ISO creation failed."
    else:
        print(GREEN + "[ Ok ]" + BLUE + " Creating Kickstart ISO" + WHITE)
        cmd = "genisoimage -relaxed-filenames -J -R -o " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + " -e images/efiboot.img -V '" + ISO_LABEL + "' -no-emul-boot " + CWD + ISO_SOURCE_EXTRACT
        assert os.system(cmd) == 0, "ISO creation failed."

if OS_DISTRO == "CentOS":
    import importlib.util 
    is_present = importlib.util.find_spec(ISOPACKAGE) #find_spec will look for the package
    if is_present is None:
        print(GREEN + "[ Ok ]" + BLUE + " Checking for ISO package handler" + WHITE)
        cmd = "yum install " + ISOPACKAGE + " -y"
        os.system(cmd)
        print(GREEN + "[ Ok ]" + BLUE + " Creating Kickstart ISO" + WHITE)
        cmd = "genisoimage -relaxed-filenames -J -R -o " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + " -e images/efiboot.img -V '" + ISO_LABEL + "' -no-emul-boot " + CWD + ISO_SOURCE_EXTRACT
        assert os.system(cmd) == 0, "ISO creation failed."
    else:
        print(GREEN + "[ Ok ]" + BLUE + " Creating Kickstart ISO" + WHITE)
        cmd = "genisoimage -relaxed-filenames -J -R -o " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME + " -e images/efiboot.img -V '" + ISO_LABEL + "' -no-emul-boot " + CWD + ISO_SOURCE_EXTRACT
        assert os.system(cmd) == 0, "ISO creation failed."

# Unmount ISO
print(GREEN + "[ Ok ]" + BLUE + " Un-mounting source ISO" + WHITE)
cmd = "umount " + CWD + ISO_SOURCE_MOUNT
os.system(cmd)

# Making hybrid ISO (no need)
# cmd = "isohybrid --uefi " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME
# os.system(cmd)
# print(GREEN + "[ Ok ]" + BLUE + " Hybridizing ISO" + WHITE)

# Implant MD5 hash
cmd = "implantisomd5 " + CWD + KICKSTART_ISO_NAME + SOURCE_ISO_NAME
assert os.system(cmd) == 0, "Implant MD5 hash failed."
print(GREEN + "[ Ok ]" + BLUE + " Embedding MD5 checksum" + WHITE)

print("")
print(GREEN + "[ Ok ]" + BLUE + " Kickstart ISO complete. The ISO file is located in the current working directory" + WHITE)
print("")
print("** PROCESS COMPLETE **")

# The end
