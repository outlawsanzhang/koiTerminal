#!/bin/bash
# Cleaning up Secureblue.

set -ex -o pipefail

# Pin latest kernel deployment
# ostree admin pin booted
# ostree admin status
# rpm-ostree status
# ostree admin pin pending # You can't.

# Set latest kernel as default and downgraded kernel as rollback
systemctl stop rpm-ostreed-automatic.timer
systemctl stop flatpak-system-update.timer
systemctl stop brew-update.timer
systemctl stop podman-auto-update.timer
rpm-ostree cancel
ostree admin status
rpm-ostree status
ostree admin set-default 1
ostree admin status
rpm-ostree status

# Set up gnome-remote-desktop
GRDCERTDIR="/var/lib/gnome-remote-desktop/.cert"
mkdir -p "${GRDCERTDIR}"
/usr/bin/openssl req -new -newkey rsa:4096 -days 7200 -nodes -x509 -subj "/C=SE/ST=NONE/L=NONE/O=GNOME/CN=gnome.org" -out "${GRDCERTDIR}/grd-tls.crt" -keyout "${GRDCERTDIR}/grd-tls.key"
chown -R gnome-remote-desktop:gnome-remote-desktop "${GRDCERTDIR}"
grdctl --system rdp set-tls-cert "${GRDCERTDIR}/grd-tls.crt"
grdctl --system rdp set-tls-key "${GRDCERTDIR}/grd-tls.key"
grdctl --system rdp disable-view-only
# TODO: run these with host-provided password in systemd
# grdctl --system rdp set-credentials droid $(cat passwd.pipe)
# systemctl restart gnome-remote-desktop
/home/linuxbrew/.linuxbrew/bin/tmux new-session -s grdctl -y 10 -d 'set -x; while grdctl --system status | grep -Pzo "RDP:\s*Status:\s*disabled"; do grdctl --system rdp enable; done' # this needs an interactive pty because they are doing `pkexec --user root` within `pkexec --user gnome-remote-desktop` which needs authentication
while /home/linuxbrew/.linuxbrew/bin/tmux capture-pane -t 'grdctl:^' -p; do sleep 1; done # wait till tmux window to exit
while ! systemctl enable gnome-remote-desktop; do sleep 5; done
grdctl --system status || true
# Allow admin features over remote desktop
cat <<EOF > /etc/polkit-1/rules.d/50-remote-auth-admin.rules
polkit.addRule(function(action, subject) {
    if (action.id.indexOf("org.debian.pcsc-lite.access") === 0) {
        return polkit.Result.NOT_HANDLED;
    }
    if (!subject.local && subject.isInGroup("wheel")) {
        return polkit.Result.AUTH_ADMIN_KEEP;
    }
});
EOF
chmod a+r /etc/polkit-1/rules.d/60-remote-auth-admin.rules
# Allow password-less login if user has no password (nopasswdlogin group maintained by koi-nopasswdlogin.service)
cp /etc/pam.d/gdm-password /etc/pam.d/gdm-password.old
echo "auth        sufficient    pam_succeed_if.so user ingroup nopasswdlogin" > /etc/pam.d/gdm-password
cat /etc/pam.d/gdm-password.old >> /etc/pam.d/gdm-password
rm /etc/pam.d/gdm-password.old
chmod 644 /etc/pam.d/gdm-password

# System update timing -- more aggressive and upfront for use as a VM
# First start 5-15 minutes after boot
sed -i -e 's/^\(RandomizedDelaySec\)=.*/\1=10m\nOnBootSec=5m/' -e 's/^\(RestartSec\)=.*/\1=5m/' /etc/systemd/system/rpm-ostreed-automatic.timer.d/override.conf
# Keep restarting every 5 minutes on failure, max 12 tries per 24h
sed -i -e 's/^\(StartLimitIntervalSec\)=.*/\1=24h/' -e 's/^\(StartLimitBurst\)=.*/\1=12/' -e 's/^\(RestartSec\)=.*/\1=5m/' /etc/systemd/system/rpm-ostreed-automatic.service.d/override.conf

# Actual cleanup

# Clean up installing scripts
rm -f /var/home/droid/*install_secureblue.sh

# Set up first-use recommendations
sed -i 's;/var/home/droid/run_install_secureblue.sh;/var/home/droid/first_use_secureblue.sh;g' /var/home/droid/.bash_profile
cat <<'EOF' > /var/home/droid/first_use_secureblue.sh
    echo 'Running `ujust audit-secureblue` to provide post-install recommendations...'
    ujust audit-secureblue --skip flatpak
    echo 'Audit complete. Please refer to the recommendations above.'
    echo
    sed -i '/\/var\/home\/droid\/first_use_secureblue.sh/d' /var/home/droid/.bash_profile
    rm -f /var/home/droid/first_use_secureblue.sh
EOF
chmod a+x /var/home/droid/first_use_secureblue.sh
chown droid:droid /var/home/droid/first_use_secureblue.sh

# Clean up gdm.service core dumps
rm -f /var/lib/systemd/coredump/*
