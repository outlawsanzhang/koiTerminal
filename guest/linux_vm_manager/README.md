`linux_vm_manager` (koiTerminal version)
----------------------------------------

### Purpose

The guest VM system service that communicates with the host app to support features such as port forwarding and vsock-based ttyd (socat and ttyd not included).

This was originally an AOSP module (Android.bp), and has been repurposed as a rust crate (Cargo.toml) built using standard rust tools,
because the build documentation for aarch64 linux (instead of aarch64 Android linking to bionic) is nowhere to be found.

In addition, the original module assumes the app has host vsock creation privileges, which koiTerminal does not have as a 3rd-party app.
Instead, the app can only use `.connectVsock()` and `.connectToVsockServer()`, so `linux_vm_manager` must `VSOCK-LISTEN`.
The host only listens to its vsock port number that is equal to the cid number of the guest, and this is arranged by privileged OS components.
`IVirtualMachineService` is available there. `VSOCK-CONNECT`ing to anything else will fail.

The JNI component `forwarder_host` within the app is therefore modified to adapt to these issues.

### Build

You'll need the following folders:
```
./android/virtualizationservice/aidl_for_non_microdroid
./libs/debian_service
./libs/libforwarder
./libs/liblinux
./guest/linux_vm_manager
./guest/forwarder_guest
./guest/forwarder_guest_launcher
./guest/shutdown_runner
```
and the following tools (assuming Debian trixie):
```
sudo apt install -y protobuf-compiler gcc-aarch64-linux-gnu
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup target add aarch64-unknown-linux-gnu
```
and the following config:
```
# In file "~/.cargo/config.toml":
[target.aarch64-unknown-linux-gnu]
linker = "aarch64-linux-gnu-gcc"
```

Build with:
```
cd ./guest/linux_vm_manager

# omit "--release" for debug build
cargo build --target aarch64-unknown-linux-gnu --release
```

Find the output at:
```
ls target/aarch64-unknown-linux-gnu/*/linux_vm_manager
```
which can be used when building VM images*.

(* Note) Alpine probably requires the `aarch64-unknown-linux-musl` target, and that setup is not tested
