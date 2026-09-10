# FAQ

To be added...

## Network settings limitations
### For all options
Port forwarding bypasses the VM's network setup and exposes user-approved ports directly on localhost.
Other apps in the same Android user profile (for Android 16 and earlier, all other apps on device) can connect to it.

### No network
On its own, this option is not sufficient to airgap the VM. The VM communicates with koiTerminal through the following channels. Leakage may exist. <!-- UPDATE -->
- Port forwarding is not automatically disabled, and can establish bidirectional communication to the outside world.
- Clipboard sharing can cause accidental information leakage.
- VSOCKs are virtual sockets that allows communication between a VM and its host if they mutually agree on its use.
    - Android Virtualization Framework (AVF) listens on a particular vsock port for the VM to [register an agent](android/virtualizationservice/aidl_for_non_microdroid/android/system/virtualmachineservice/IVirtualMachineService.aidl). AVF can ask the agent to [perform certain tasks](android/virtualizationservice/aidl/android/system/virtualizationcommon/IGuestAgent.aidl), such as shutdown or dump logs, or notify it of certain events.
    - AVF confines all other VSOCK communication with the VM to the app that spawns it (`connectVsock`/`connectToVsockServer`).
      For now, koiTerminal connects to (1) the [VM service](libs/debian_service/aidl/com/android/virtualization/debian/aidl/IDebianService.aidl) from the vanilla Linux Terminal app for port forwarding, and clipboard sharing, (2) the ttyd service guarded by a [random password](android/TerminalApp/java/com/android/virtualization/koiterminal/AndroidToVmBridge.kt), and (3) an [additional service](libs/debian_service/aidl/com/android/virtualization/debian/aidl/IkoiService.aidl) for hosting the SOCKS5 proxy which is turned off in this mode.
- Virtual hardware also facilitates communication: consoles, mouse, keyboard, display, sound, and disk images.

Do note that there could be many covert channels between malicious apps on the VM and the host, and many of them unfixable.
For example, they can use CPU usage as a noisy analog signal medium.
Linux apps can monitor steal time (CPU usage by the host and other VMs), Android apps can monitor battery usage, and both can run stress tests to emit signals.

### Raw network
This is the default used by the vanilla Linux Terminal app.
It uses a new TAP interface, and bypasses the VPN on Android.

You can set up a VPN inside the VM, but it will be up to the VM to prevent leakage, implement a killswitch, and uphold the integrity of its network setup. Good luck.

### Route through app
If you do not use a VPN, there is no benefit to using this option.
This option uses the app's default network connection. Android determines the network used, which should be the VPN when one is active.

koiTerminal-maintained VM images older than 202608* do not support this option out of the box.

An option to block traffic to loopback addresses is provided, but it does not stop the VM connecting to other apps through other means.
If the VM finds out the LAN IP of your device, it can still connect to other apps.
It can also try to correlate your activities (Android apps and the VM shares the same VPN IP) and pass data through an external party.

Some uncommon operations such as `ping` will not work, since not all network protocols are supported by SOCKS5. It can only forward TCP and UDP traffic.
But the vast majority of applications will work normally.
koiTerminal forwards only TCP traffic initially (SOCKS5 provides DNS). When UDP is later supported, there will be a toggle for [leaky operations such as multicast](https://support.torproject.org/tor-vpn/security/threat-model/#513-multicast-dns-and-ssdp-leaks).

[DNS leak](https://support.torproject.org/tor-vpn/security/threat-model/#524-various-dns-leaks) could be an issue on stock OS, since the socks5 proxy uses libc. But so does Chrome and everyone else, apparently.

### Custom proxy
The third-party SOCKS5 proxy is solely responsible for preventing network leakage, including [DNS leakage](https://support.torproject.org/tor-vpn/security/threat-model/#524-various-dns-leaks).

The default port is what Orbot hosts for its SOCKS5 proxy. You can enable it at More > Orbot Settings > General > Power User Mode.

Only proxies on localhost are supported.

Only TCP is supported for now.
