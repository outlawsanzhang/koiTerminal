#!/bin/bash
# Setting up Secureblue kernel arguments.

set -ex -o pipefail

rpm-ostree cancel
# https://secureblue.dev/post-install
echo -e 'y\ny\nn' | ujust set-kargs-hardening
# Note:
#   module.sig_enforce: potential upstream issue where this flag is left out
#   console=hvc0: see notes in step 5
rpm-ostree kargs --append-if-missing=module.sig_enforce=1 --append-if-missing=console=hvc0
# leave out --append-if-missing=ia32_emulation=0 for now
