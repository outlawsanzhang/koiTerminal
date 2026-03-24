# Building the Secureblue image
Note: Secureblue support for ARM64 is in Beta, and this build guide may change accordingly.

This one is hard because there is no VM image for either Fedora Atomic or Secureblue to be found,
or any build guide elsewhere. All references are credited in the comments.
Currently the official recommendation is to install Fedora Atomic and rebase.
The rebase command does not verify Secureblue signatures, so verification is a work in progress.

The [build script](build/custom_vm/secureblue/build.sh) uses Kickstart to install Fedora Atomic,
then uses QEMU system emulation to perform the rebase and post-installation setup.
It again assumes a x64 Debian trixie environment (VM or docker recommended),
and that [the requirements here](build/custom_vm/generic-kernel/README.md#dependencies) are installed.
(Probably a subset is sufficient; not tested)

See [this page](build/custom_vm/u-boot/README.md) for the `u-boot.bin` binary.
The provided image builds u-boot from source.

Due to the abysmal speed of system emulation, the build process takes hours.
It also requires about 60GB of storage: although the final image is not very large,
a lot of files are created and deleted during installation,
making the qcow2 images bloat with deleted data.
Reducing the image size requires further storage on the build machine.

If anyone knows how this process can be simplified without affecting the end result, please let the developer know.

### Abandoned attempts
- `butane.yaml` is an attempt at building Secureblue by rebasing from a Fedora CoreOS image.
  This path turns out to be unsupported.
  - `securecore-build.sh` repurposes this butane script for Securecore. This is untested for bugs and put on hold.
- `config-template.toml` is an attempt at building Secureblue using `bootc-image-builder`.
  This method was promising because the [official testing mechanism](https://github.com/secureblue/bootc-integration-test-action) uses it too.
  However, there seems to be no support or guarantee that the resulting image is similar to what we get by rebasing from a Silverblue install.

