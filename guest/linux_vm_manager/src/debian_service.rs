// Copyright 2026, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use anyhow::anyhow;
use rsbinder::*;
use crate::aidl;
use {
    aidl::com::android::virtualization::debian::aidl::{
        IDebianService::{BnDebianService, IDebianService, VSOCK_PORT},
        IkoiService::{BnIkoiService, IkoiService, VSOCK_PORT as KOI_VSOCK_PORT},
        IkoiHostCallback::{IkoiHostCallback},
        IVmActivePortListener::ActivePort::ActivePort,
        IVmActivePortListener::IVmActivePortListener,
    },
};
use forwarder::stream::{AsyncForwarderSession};
use log::{debug, error, info, warn};
use rsbinder::rpc::{RpcServer, wire_android13::PROTOCOL_V2};
use std::collections::{BTreeMap, HashSet};
use std::collections::btree_map::Entry as BTreeMapEntry;
use std::future::Future;
use std::net::{Ipv4Addr, Ipv6Addr, IpAddr};
use std::os::unix::io::AsRawFd;
use std::sync::{Arc, LazyLock, Mutex};
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tokio::task;
use vsock::VsockListener;

static MANAGED_PORTS: LazyLock<Mutex<HashSet<u16>>> = LazyLock::new(|| Mutex::new(HashSet::new()));

pub struct DebianService {
    rt: Runtime,
}

impl Interface for DebianService {}

impl DebianService {
    pub fn new_rpc_server() -> Arc<RpcServer> {
        let rt = create_tokio_runtime();
        let service = DebianService { rt };

        let binder = BnDebianService::new_binder_with_features(service, BinderFeatures::default());

        let vsock_port: u32 = VSOCK_PORT.try_into().unwrap();
        let socket = "/tmp/IDS.socket";
        std::fs::remove_file(socket).ok();
        info!("Setting up debian service rpc server at {socket}");
        let server = RpcServer::setup_unix_server(socket)
            .inspect_err(|e| error!("Failed to start debian service rpc server at {socket}: {e}")).unwrap();
        server.set_android13plus(PROTOCOL_V2);
        if let Err(e) = std::process::Command::new("socat")
            .arg("-d0")
            .arg(format!("VSOCK-LISTEN:{vsock_port},reuseaddr,fork"))
            .arg(format!("UNIX-CONNECT:{socket}"))
            .spawn() {
            error!("Could not spawn socat VSOCK-LISTEN:{vsock_port} <> {socket}: {e:?}");
        }
        info!("Spawned socat VSOCK-LISTEN:{vsock_port} <> {socket}");
        server.set_max_threads(4);
        server.set_root(binder.as_binder());
        server.run_background();
        server
    }
}

fn create_tokio_runtime() -> Runtime {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .max_blocking_threads(8192) // Each socks5 connection has 2 spawn_blocking threads...
        .build()
        .expect("Failed to create tokio runtime")
}

// To workaround two Rust compiler errors:
//   - higher-ranked life time error
//   - Implementation of Send is not general enough
fn force_send<F: Future + Send>(f: F) -> impl Future<Output = F::Output> + Send {
    f
}

fn forward_port(tcp_port: u16, vsock_port: u32) {
    // Use std::process::Command which doesn't require Tokio context.
    info!("Forwarding port with socat: TCP-CONNECT:127.0.0.1:{tcp_port} <> VSOCK-LISTEN:{vsock_port}");
    if let Err(e) = std::process::Command::new("socat")
        .arg(format!("TCP-CONNECT:127.0.0.1:{tcp_port}"))
        .arg(format!("VSOCK-LISTEN:{vsock_port}"))
        .spawn()
    {
        error!("Failed to launch socat with port forwarding mode, tcp_port={tcp_port}, vsock_port={vsock_port}, err={e:?}");
    }
}

impl IDebianService for DebianService {
    fn setVmActivePortListener(
        &self,
        listener: &Strong<dyn IVmActivePortListener>,
    ) -> BinderResult<()> {
        let listener = listener.clone();
        self.rt.spawn(async move {
            let managed_ports = match MANAGED_PORTS.lock() {
                Ok(managed_ports) => managed_ports.clone(),
                Err(_) => HashSet::new(),
            };
            let ret =
                force_send(forwarder_guest_launcher::monitor_active_ports(async move |ports| {
                    let ports: Vec<ActivePort> = ports
                        .iter()
                        .filter(|(x, _)| !managed_ports.contains(*x))
                        .map(|(port, comm)| ActivePort {
                            port: *port as i32,
                            comm: comm.to_string(),
                        })
                        .collect();

                    info!("Updated active ports: {ports:?}");
                    listener
                        .reportActivePorts(&ports)
                        .map_err(|e| anyhow!("Error in reportActivePorts(), {e:?}"))
                }))
                .await;

            match ret {
                Ok(()) => warn!("monitor_active_ports is unexpectedly returned"),
                Err(e) => warn!(
                    "monitor_active_ports is returned with error, e={e:?}. May be shutting down."
                ),
            };
        });

        Ok(())
    }

    fn requestForwarding(&self, guest_tcp_port: i32, vsock_port: i32) -> BinderResult<()> {
        let tcp_port = guest_tcp_port
            .try_into()
            .expect("Failed to call requestForwarding(): {guest_tcp_port} out of range");
        forward_port(tcp_port, vsock_port as u32);
        Ok(())
    }

    fn requestStorageBalloon(&self, _available_bytes: i64) -> BinderResult<()> {
        warn!("requestStorageBalloon not supported");
        Err(Status::new_service_specific_error(-1, None))
    }

    fn updateClipboard(&self, text: &str) -> BinderResult<()> {
        use std::io::Write;
        use std::process::{Command, Stdio};

        let mut child = Command::new("sudo")
            .args([
                "-u",
                "droid",
                "XDG_RUNTIME_DIR=/run/user/1000",
                "WAYLAND_DISPLAY=wayland-0",
                "wl-copy",
            ])
            .stdin(Stdio::piped())
            .spawn()
            .map_err(|e| {
                error!("Failed to spawn wl-copy: {e:?}");
                Status::new_service_specific_error(-1, None)
            })?;

        let mut stdin = child.stdin.take().unwrap();
        stdin.write_all(text.as_bytes()).map_err(|e| {
            error!("Failed to write to wl-copy stdin: {e:?}");
            Status::new_service_specific_error(-1, None)
        })?;

        drop(stdin); // Send EOF

        let status = child.wait().map_err(|e| {
            error!("Failed to wait for wl-copy: {e:?}");
            Status::new_service_specific_error(-1, None)
        })?;

        if !status.success() {
            error!("wl-copy failed with status: {status:?}");
            return Err(Status::new_service_specific_error(-1, None));
        }

        Ok(())
    }

    fn readClipboard(&self) -> BinderResult<String> {
        use std::process::Command;

        debug!("Reading guest clipboard");

        let output = Command::new("sudo")
            .args([
                "-u",
                "droid",
                "XDG_RUNTIME_DIR=/run/user/1000",
                "WAYLAND_DISPLAY=wayland-0",
                "wl-paste",
                "--no-newline",
            ])
            .output()
            .map_err(|e| {
                error!("Failed to execute wl-paste: {e:?}");
                Status::new_service_specific_error(-1, None)
            })?;

        if !output.status.success() {
            // wl-paste may fail if clipboard is empty, return empty string in that case
            debug!("wl-paste returned non-success status: {:?}", output.status);
            return Ok(String::new());
        }

        let text = String::from_utf8_lossy(&output.stdout).into_owned();
        Ok(text)
    }
}

type SharedIkoiHostCallback = Arc<Mutex<Strong<dyn IkoiHostCallback>>>;
pub struct KoiService {
    rt: Runtime,
    // First mutex is for registering / cloning it, second mutex is for using it
    callback: Mutex<Option<SharedIkoiHostCallback>>,
    registered_ports: Mutex<BTreeMap<u16, task::JoinHandle<Result<()>>>>,
}

impl Interface for KoiService {}

pub struct TcpListeners {
    listener_v4: Arc<TcpListener>,
    listener_v6: Arc<TcpListener>,
    connect_jobs: task::JoinSet<Result<(TcpStream, Arc<TcpListener>)>>,
}

impl TcpListeners {
    async fn bind(port: u16) -> Result<TcpListeners> {
        Ok(TcpListeners {
            listener_v4: Arc::new(TcpListener::bind((IpAddr::V4(Ipv4Addr::LOCALHOST), port)).await
                .inspect_err(|e| error!("Cannot listen to TCP IPv4 port {port}: {e}"))?),
            listener_v6: Arc::new(TcpListener::bind((IpAddr::V6(Ipv6Addr::LOCALHOST), port)).await
                .inspect_err(|e| error!("Cannot listen to TCP IPv6 port {port}: {e}"))?),
            connect_jobs: task::JoinSet::new(),
        })
    }

    async fn accept(&mut self) -> anyhow::Result<TcpStream> {
        if self.connect_jobs.is_empty() {
            let listener_v4 = self.listener_v4.clone();
            let listener_v6 = self.listener_v6.clone();
            self.connect_jobs.spawn(async move {
                Ok((listener_v4.accept().await?.0, listener_v4))
            });
            self.connect_jobs.spawn(async move {
                Ok((listener_v6.accept().await?.0, listener_v6))
            });
        }
        match self.connect_jobs.join_next().await {
            None => anyhow::bail!("Error joining TCP listener jobs"),
            Some(Err(e)) => Err(anyhow::Error::new(e).context("Error joining TCP listener jobs")),
            Some(Ok(Err(e))) => Err(anyhow::Error::new(e).context("Error accepting TCP connection")),
            Some(Ok(Ok((socket, listener)))) => {
                self.connect_jobs.spawn(async {
                    Ok((listener.accept().await?.0, listener))
                });
                Ok(socket)
            }
        }
    }
}

impl KoiService {
    pub fn new_rpc_server() -> std::sync::Arc<RpcServer> {
        let rt = create_tokio_runtime();
        let service = KoiService {
            rt,
            callback: Mutex::new(None),
            registered_ports: Mutex::new(BTreeMap::new()),
        };

        let binder = BnIkoiService::new_binder(service);

        let vsock_port: u32 = KOI_VSOCK_PORT.try_into().unwrap();
        let socket = "/tmp/IkS.socket";
        std::fs::remove_file(socket).ok();
        info!("Setting up koi service rpc server at {socket}");
        let server = RpcServer::setup_unix_server(socket)
            .inspect_err(|e| error!("Failed to start debian service rpc server at {socket}: {e}")).unwrap();
        server.set_android13plus(PROTOCOL_V2);
        if let Err(e) = std::process::Command::new("socat")
            .arg("-d0")
            .arg(format!("VSOCK-LISTEN:{vsock_port},reuseaddr,fork"))
            .arg(format!("UNIX-CONNECT:{socket}"))
            .spawn() {
            error!("Could not spawn socat VSOCK-LISTEN:{vsock_port} <> {socket}: {e:?}");
        }
        info!("Spawned socat VSOCK-LISTEN:{vsock_port} <> {socket}");
        server.set_max_threads(4);
        server.set_root(binder.as_binder());
        server.run_background();
        server
    }

    async fn reverse_connect_once(
        listeners: &mut TcpListeners,
        vlistener: &VsockListener,
        port: u16,
        callback: SharedIkoiHostCallback,
    ) -> anyhow::Result<AsyncForwarderSession> {
        let mut socket = listeners.accept().await
            .inspect_err(|e| error!("Cannot accept connection to TCP port {port}: {e}"))?;
        Self::request_reverse_connection(callback, port.into())
            .inspect_err(|e| error!("Cannot request vsock connection on port {port} from host: {e}"))?;
        let vsock_connection = vlistener.accept(); // TODO: this is blocking, not async...
        if let Err(e) = vsock_connection {
            error!("Cannot request vsock connection on port {port} from host: {e}");
            socket.shutdown().await
                .inspect_err(|e| error!("Cannot shutdown connection on TCP port {port}: {e}")).ok();
            return Err(anyhow!(e));
        }
        let (vsock, _) = vsock_connection.unwrap();
        // note: socket is already nonblocking
        vsock.set_nonblocking(false)
            .inspect_err(|e| error!("Cannot set vsock connection on port {port} to blocking: {e}"))?;
        let socket = socket.into_std()
            .inspect_err(|e| error!("Cannot convert tokio::net::TcpStream to std::net::TcpStream: {e}"))?;
        socket.set_nonblocking(false)
            .inspect_err(|e| error!("Cannot set tcp connection on port {port} to blocking: {e}"))?;
        Ok(AsyncForwarderSession::new(
            socket.as_raw_fd(),
            vsock.as_raw_fd(),
            Some(vsock),
            Some(socket),
            port
        ))
    }

    async fn run_reverse_connection_listener(
        port: u16, callback: SharedIkoiHostCallback,
    ) -> Result<()> {
        let mut listeners = TcpListeners::bind(port).await?;
        let vlistener = VsockListener::bind_with_cid_port(vsock::VMADDR_CID_ANY, port.into())
            .inspect_err(|e| error!("Cannot listen to vsock port {port}: {e}"))?;
        loop {
            if let Ok(session) = Self::reverse_connect_once(
                &mut listeners, &vlistener, port, callback.clone()
            ).await {
                task::spawn(async move { AsyncForwarderSession::run(session).await });
            }
        }
    }

    fn get_callback_arc(&self) -> anyhow::Result<Arc<Mutex<Strong<dyn IkoiHostCallback>>>> {
        match self.callback.lock() {
            Ok(callback) => match *callback {
                Some(ref callback) => Ok(callback.clone()),
                None => Err(anyhow!("koiService callback not yet registered")),
            },
            Err(e) => Err(anyhow!("koiService callback usage mutex lock failed: {e}")),
        }
    }

    fn register_reverse_connection_port(&self, vsock_port: u16) -> BinderResult<()> {
        let callback = self.get_callback_arc().map_err(|e| {
            error!("Failed to obtain koiService callback: {e}");
            Status::new_service_specific_error(-1, None)
        })?;
        let mut registered_ports = self.registered_ports.lock().map_err(|e| {
            error!("Failed to lock mutex registered_ports: {e}");
            Status::new_service_specific_error(-1, None)
        })?;
        let mut managed_ports = MANAGED_PORTS.lock().map_err(|e| {
            error!("Failed to lock mutex MANAGED_PORTS: {e}");
            Status::new_service_specific_error(-1, None)
        })?;
        if let BTreeMapEntry::Vacant(o) = registered_ports.entry(vsock_port) {
            if !managed_ports.contains(&vsock_port) {
                managed_ports.insert(vsock_port);
                o.insert(self.rt.spawn(Self::run_reverse_connection_listener(vsock_port, callback)));
                return Ok(())
            }
        }
        error!("Reverse connection port {vsock_port} already occupied");
        Err(Status::new_service_specific_error(-1, None))
    }

    fn request_reverse_connection(
        callback: SharedIkoiHostCallback,
        vsock_port: i32
    ) -> BinderResult<()> {
        match callback.lock() {
            Ok(callback) => {
                callback.requestReverseConnection(vsock_port)?;
                Ok(())
            },
            Err(e) => {
                error!("koiService callback usage mutex lock failed: {e}");
                Err(Status::new_service_specific_error(-1, None))
            },
        }
    }
}

impl IkoiService for KoiService {
    fn registerHostCallback(
        &self,
        callback: &Strong<dyn IkoiHostCallback>,
    ) -> BinderResult<()> {
        info!("koiService: attaching host callback");
        match self.callback.lock() {
            Ok(mut callback_option) => {
                *callback_option = Some(Arc::new(Mutex::new(callback.clone())));
                Ok(())
            },
            Err(e) => {
                error!("koiService callback assignment mutex lock failed: {e}");
                Err(Status::new_service_specific_error(-1, None))
            }
        }
    }

    fn openReverseConnectedPort(&self, vsock_port: i32) -> BinderResult<()> {
        #[allow(overflowing_literals)]
        let port = vsock_port as u16;
        info!("koiService: registering reverse-connected port {vsock_port} (port {port})");
        self.register_reverse_connection_port(port)
    }

    fn supportsStorageBalloon(&self) -> BinderResult<bool> {
        Ok(false)
    }
}
