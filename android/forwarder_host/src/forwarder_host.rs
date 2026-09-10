// Copyright 2024 The Android Open Source Project
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

// Copied from ChromiumOS with relicensing:
// src/platform2/vm_tools/chunnel/src/bin/chunneld.rs

//! Host-side stream socket forwarder

use std::collections::btree_map::Entry as BTreeMapEntry;
use std::collections::{BTreeMap, BTreeSet, HashMap, VecDeque};
use std::error::Error as stdError;
use std::fmt;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
use std::result;
use std::sync::{Arc, LazyLock, Mutex};

use android_logger::Config;
use fast_socks5::{
    server::{DnsResolveHelper as _, ErrorContext, Socks5ServerProtocol, SocksServerError, states}, // , run_udp_proxy
    util::{stream::{ConnectError, tcp_connect_with_timeout}, target_addr::TargetAddr},
    ReplyError, Result as SocksResult, Socks5Command, // , SocksError
};
use forwarder::forwarder::ForwarderSession;
use forwarder::stream::{AsyncBorrowedFdStream, AsyncForwarderSession, StreamSocket};
use jni::objects::{JClass, JIntArray, JObject, JValue, JValueOwned};
use jni::sys::jint;
use jni::JNIEnv;
use log::{debug, error, info, warn, LevelFilter};
use nix::sys::eventfd::{EfdFlags, EventFd};
use poll_token_derive::PollToken;
use tokio::runtime::Runtime;
use vmm_sys_util::poll::{PollContext, PollToken};

static SHUTDOWN_EVT: LazyLock<EventFd> =
    LazyLock::new(|| EventFd::new().expect("Could not create shutdown eventfd"));

static UPDATE_EVT: LazyLock<EventFd> = LazyLock::new(|| {
    EventFd::from_flags(EfdFlags::EFD_SEMAPHORE).expect("Could not create update eventfd")
});

static UPDATE_QUEUE: LazyLock<Arc<Mutex<VecDeque<u16>>>> =
    LazyLock::new(|| Arc::new(Mutex::new(VecDeque::new())));

static REVCONN_EVT: LazyLock<EventFd> = LazyLock::new(|| {
    EventFd::from_flags(EfdFlags::EFD_SEMAPHORE).expect("Could not create reverse-connect eventfd")
});

static REVDISC_EVT: LazyLock<EventFd> = LazyLock::new(|| {
    EventFd::from_flags(EfdFlags::EFD_SEMAPHORE).expect("Could not create reverse-disconnect eventfd")
});

static REVCONN_QUEUE: LazyLock<Arc<Mutex<VecDeque<i32>>>> =
    LazyLock::new(|| Arc::new(Mutex::new(VecDeque::new())));

static REVDISC_QUEUE: LazyLock<Arc<Mutex<VecDeque<RawFd>>>> =
    LazyLock::new(|| Arc::new(Mutex::new(VecDeque::new())));

#[remain::sorted]
#[derive(Debug)]
enum Error {
    JNI(jni::errors::Error),
    NoListenerForPort(u16),
    NoRcServiceForPort(i32),
    NoSessionForTag(SessionTag),
    PollContextAdd(vmm_sys_util::errno::Error),
    PollContextDelete(vmm_sys_util::errno::Error),
    PollContextNew(vmm_sys_util::errno::Error),
    PollWait(vmm_sys_util::errno::Error),
    RcsEventRead(nix::Error),
    TcpAccept(io::Error),
    TcpListenerPort(io::Error),
    UpdateEventRead(nix::Error),
    VsockConnect(jni::errors::Error),
    VsockDisconnect(jni::errors::Error),
}

type Result<T> = result::Result<T, Error>;

impl fmt::Display for Error {
    #[remain::check]
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        use self::Error::*;

        #[remain::sorted]
        match self {
            JNI(e) => {
                let e_src = e.source();
                write!(f, "JNI error: {e:#?} ({e}), {e_src:#?}")
            },
            NoListenerForPort(port) => write!(f, "could not find listener for port: {port}"),
            NoRcServiceForPort(port) => write!(f, "could not find reverse-connected service for vsock port: {port:x}"),
            NoSessionForTag(tag) => write!(f, "could not find session for tag: {tag:x}"),
            PollContextAdd(e) => write!(f, "failed to add fd to poll context: {e}"),
            PollContextDelete(e) => write!(f, "failed to delete fd from poll context: {e}"),
            PollContextNew(e) => write!(f, "failed to create poll context: {e}"),
            PollWait(e) => write!(f, "failed to wait for poll: {e}"),
            RcsEventRead(e) => write!(f, "failed to read reverse-connected service eventfd: {e}"),
            TcpAccept(e) => write!(f, "failed to accept tcp: {e}"),
            TcpListenerPort(e) => {
                write!(f, "failed to read local sockaddr for tcp listener: {e}")
            }
            UpdateEventRead(e) => write!(f, "failed to read update eventfd: {e}"),
            VsockConnect(e) => {
                let e_src = e.source();
                write!(f, "failed to connect vsock: {e:#?} ({e}), {e_src:#?}")
            },
            VsockDisconnect(e) => {
                let e_src = e.source();
                write!(f, "failed to disconnect vsock: {e:#?} ({e}), {e_src:#?}")
            },
        }
    }
}

/// A tag that uniquely identifies a particular forwarding session. This has arbitrarily been
/// chosen as the fd of the local (TCP) socket.
type SessionTag = u32;

/// Implements PollToken for chunneld's main poll loop.
#[derive(Clone, Copy, PollToken)]
enum Token {
    Shutdown,
    UpdatePorts,
    ReverseConnect,
    ReverseDisconnect,
    Ipv4Listener(u16),
    Ipv6Listener(u16),
    LocalSocket(SessionTag),
    RemoteSocket(SessionTag),
}

/// PortListeners includes all listeners (IPv4 and IPv6) for a given port, and the target
/// container.
struct PortListeners {
    tcp4_listener: TcpListener,
    tcp6_listener: TcpListener,
}

/// SocketFamily specifies whether a socket uses IPv4 or IPv6.
enum SocketFamily {
    Ipv4,
    Ipv6,
}

/// ReverseConnectedService gets connections from vsock
trait ReverseConnectedService {
    /// Returns vsock port
    fn get_vsock_port(&self) -> i32;
    /// Can return a session to run, or just read from it directly. Is responsible for closing fd via REVDISC_*.
    fn on_new_connection(&mut self, fd: RawFd) -> Result<Option<ForwarderSession>>;
}

struct RcSocks5Proxy {
    vsock_port: i32,
    setup: Arc<Socks5Setup>,
    rt: Arc<Runtime>,
}

macro_rules! try_notify {
    ($proto:expr, $e:expr) => {
        match $e {
            Ok(res) => res,
            Err(err) => {
                if let Err(rep_err) = $proto.reply_error(&err.to_reply_error()).await {
                    error!(
                        "extra error while reporting an error to the client: {}",
                        rep_err
                    );
                }
                return Err(err.into());
            }
        }
    };
}

/// An attempt to find out what are private addresses.
/// This differs from the ACCESS_LOCAL_NETWORK permission, so should be handled by Android instead.
#[allow(dead_code)]
fn is_global_ip(ip: &IpAddr, allow_protocols: bool) -> bool {
    // See Ipv4Addr::is_global() and Ipv6Addr::is_global()
    // allow_protocols: whether uncommon protocol-specific addresses are allowed; specify false to be safer
    if ip.is_unspecified() || ip.is_loopback() {
        return false;
    }
    match ip {
        IpAddr::V4(ip) => {
            if ip.is_private() || ip.is_link_local() || ip.is_documentation() || ip.is_broadcast() {
                return false;
            }
            match ip.octets() {
                [0, _, _, _] => false, // "this network"
                [100, 64..=127, _, _] => false, // is_shared
                [192, 0, 0, d] => (d == 9 || d == 10) && allow_protocols, // reserved, except .9 and .10
                [198, 18..=19, _, _] => false, // is_benchmarking
                [240..=255, _, _, _] => false, // is_reserved
                _ => true,
            }
        },
        IpAddr::V6(ip) => {
            if ip.is_unique_local() || ip.is_unicast_link_local() {
                return false;
            }
            match ip.segments() {
                [0, 0, 0, 0, 0, 0xffff, ..] => false, // IPv4-mapped Address
                [0x64, 0xff9b, 1, ..] => false, // IPv4-IPv6 Translat.
                [0x100, 0, 0, 0, ..] => false, // Discard-Only Address Block
                // It seems that VPN leakage is attributed to multicast, so anycast is fine for now.
                // It does sound like anycast should be routed to the other side of the VPN tunnel.
                // I sure hope Android does not decide to route any of these outside the VPN.
                [0x2001, 1, 0, 0, 0, 0, 0, 1..=2] => allow_protocols, // PCP Anycast, TURN Anycast
                [0x2001, 3, ..] => allow_protocols, // AMT public relay anycast
                [0x2001, 4, 0x112, ..] => allow_protocols, // AS112-v6 (DNS sinks)
                [0x2001, 0x20..=0x3f, ..] => allow_protocols, // ORCHIDv2; Drone Remote ID Protocol Entity Tags
                [0x2001, ..] => false, // IETF Protocol Assignments, all others, including is_benchmarking and part of is_documentation
                [0x2002, ..] => false, // 6to4
                [0x3fff, 0..=0x0fff, ..] => false, // is_documentation
                [0x5f00, ..] => false, // Segment Routing (SRv6) SIDs
                _ => true,
            }
        },
    }
}

impl RcSocks5Proxy {
    fn new(vsock_port: i32, setup: Arc<Socks5Setup>, rt: Arc<Runtime>) -> Self {
        Self {
            vsock_port,
            setup,
            rt,
        }
    }

    async fn run_tcp_proxy_blocking<'a>(
        proto: Socks5ServerProtocol<AsyncBorrowedFdStream<'a>, states::CommandRead>,
        addr: &TargetAddr,
        setup: Arc<Socks5Setup>,
        nodelay: bool,
    ) -> result::Result<(), SocksServerError> {
        let addr = try_notify!(
            proto,
            addr.to_socket_addrs()
                .err_when("converting to socket addr")
                .and_then(|mut addrs| addrs.next().ok_or(SocksServerError::Bug("no socket addrs")))
                .and_then(|addr|
                    // Check if address is allowed
                    if setup.allows_address(&addr.ip()) { Ok(addr) }
                    else { Err(SocksServerError::ConnectError(ConnectError::ConnectionReset(io::Error::from_raw_os_error(103)))) } // libc::ECONNABORTED = 103, libc::ECONNRESET = 104
                )
        );

        // TCP connect with timeout, to avoid memory leak for connection that takes forever
        let outbound = match tcp_connect_with_timeout(addr, setup.timeout).await {
            Ok(stream) => stream,
            Err(err) => {
                proto.reply_error(&err.to_reply_error()).await?;
                return Err(err.into());
            }
        };

        // Disable Nagle's algorithm if config specifies to do so.
        try_notify!(
            proto,
            outbound.set_nodelay(nodelay).err_when("setting nodelay")
        );

        debug!("Socks5 connected to remote destination");

        let inner = proto
            .reply_success(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 0))
            .await?;
        let outbound = outbound.into_std().map_err(|e| {
            error!("Cannot convert tokio::net::TcpStream to std::net::TcpStream: {e}");
            SocksServerError::Bug("Cannot convert tokio::net::TcpStream to std::net::TcpStream")
        })?;
        outbound.set_nonblocking(false)
            .inspect_err(|e| error!("Cannot set tcp connection to blocking: {e}")).ok();

        let session = AsyncForwarderSession::new(
            inner.as_raw_fd(),
            outbound.as_raw_fd(),
            None,
            Some(outbound),
            0
        );
        std::mem::drop(inner);
        AsyncForwarderSession::run(session).await
            .inspect_err(|e| error!("fast-socks5 running forwarder session error: {e}"))
            .map_err(|_| SocksServerError::Bug("fast-socks5 running forwarder session error"))?;
        Ok(())
    }

    async fn run_new_connection_blocking(fd: RawFd, setup: Arc<Socks5Setup>) -> SocksResult<()> {
        // Safety: only other operation is closing the vsock, which happens outside the scope of `stream`
        let stream = unsafe { AsyncBorrowedFdStream::new(&fd) }?;
        // Authentication not needed since there is no exposed port. All connection via vsock as file descriptors
        let (proto, cmd, target_addr) = Socks5ServerProtocol::accept_no_auth(stream)
            .await.inspect_err(|e| error!("fast-socks5 new connection accept error: {e}"))?
            .read_command().await.inspect_err(|e| error!("fast-socks5 new connection read command error: {e}"))?
            .resolve_dns().await.inspect_err(|e| error!("fast-socks5 new connection dns error: {e}"))?;
        match cmd {
            Socks5Command::TCPConnect => {
                Self::run_tcp_proxy_blocking(proto, &target_addr, setup, true).await.inspect_err(|e| error!("fast-socks5 connection running error: {e}"))?;
            }
            Socks5Command::UDPAssociate => {
                // // TODO: UDP requires a jni_cb member function like
                // // "onUdpBindForwardingRequestReceived",
                // // "()I",
                // // and let guest decide the udp/vsock port to use.
                // // Guest will need to listen on the udp port and forward it to the vsock port,
                // // and host will need to pass that somehow to a very custom version of run_udp_proxy.
                // // Will implement later.
                // run_udp_proxy_vsock_version(proto, &target_addr, None, "127.0.0.1", None).await?;
                if setup.udp {
                    error!("UDP enabled, but not implemented");
                }
                proto.reply_error(&ReplyError::CommandNotSupported).await.inspect_err(|e| error!("fast-socks5 new connection reply_error error: {e}"))?;
                return Err(ReplyError::CommandNotSupported.into());
            }
            _ => {
                proto.reply_error(&ReplyError::CommandNotSupported).await.inspect_err(|e| error!("fast-socks5 new connection reply_error error: {e}"))?;
                return Err(ReplyError::CommandNotSupported.into());
            }
        };
        Ok(())
    }
}

impl ReverseConnectedService for RcSocks5Proxy {
    fn get_vsock_port(&self) -> i32 {
        self.vsock_port
    }

    fn on_new_connection(&mut self, fd: RawFd) -> Result<Option<ForwarderSession>> {
        let setup = self.setup.clone();
        // Delegated proxy:
        if setup.delegated != 0 {
            let tcp_stream = TcpStream::connect((Ipv4Addr::LOCALHOST, setup.delegated)).map_err(Error::TcpAccept)?;
            // Safety: fd only used in rust, and closing in jni is called when done, and specified not handling closing
            let mut vsock_stream = unsafe { StreamSocket::from_raw_fd(fd) };
            vsock_stream.handles_closing = false;
            return Ok(Some(ForwarderSession::new(tcp_stream.into(), vsock_stream)));
        }
        // Managed proxy:
        // AsyncBorrowedFdStream pretends to be an async task but is actually blocking,
        // and cannot be implemented as non-blocking due to Android restrictions on
        // file descriptor set non-blocking and query buffer emptiness
        self.rt.spawn_blocking(move || {
            let result = tokio::runtime::Handle::current().block_on(async move {
                Self::run_new_connection_blocking(fd, setup).await
            });
            let mut revdisc_queue = REVDISC_QUEUE.lock().unwrap();
            revdisc_queue.push_back(fd);
            REVDISC_EVT.write(1).expect("failed to write reverse-disconnect eventfd");
            result
        });
        Ok(None)
    }
}

/// ForwarderSessions encapsulates all forwarding state for chunneld.
struct ForwarderSessions<'a> {
    listening_ports: BTreeMap<u16, PortListeners>,
    tcp4_forwarders: HashMap<SessionTag, ForwarderSession>,
    reverse_connected_services: BTreeMap<i32, Mutex<Box<dyn ReverseConnectedService + 'static>>>,
    cid: u32,
    jni_env: JNIEnv<'a>,
    jni_cb: JObject<'a>,
    rt: Arc<Runtime>,
}

impl<'a> ForwarderSessions<'a> {
    /// Creates a new instance of ForwarderSessions.
    fn new(cid: i32, jni_env: JNIEnv<'a>, setup: JObject, jni_cb: JObject<'a>) -> Result<Self> {
        let mut session = ForwarderSessions {
            listening_ports: BTreeMap::new(),
            tcp4_forwarders: HashMap::new(),
            reverse_connected_services: BTreeMap::new(),
            cid: cid as u32,
            jni_env,
            jni_cb,
            rt: Arc::new(
                tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .build()
                    .expect("Failed to create tokio runtime")
            ),
        };
        let setup = ForwarderHostSetup::from_java(&mut session.jni_env, setup)
            .map_err(|e| { error!("Cannot parse ForwarderHostSetup: {e}"); Error::JNI(e) })?;
        debug!("ForwarderSessions: received ForwarderHostSetup from Kotlin = {setup:?}");
        if setup.rcServices.contains(&setup.REVCONN_SOCKS5_PROXY) {
            info!("ForwarderSessions: setting up reverse-connected socks5 proxy at vsock port {}", setup.SOCKS5_PORT);
            session.include_reverse_connected_service(
                Box::new(RcSocks5Proxy::new(setup.SOCKS5_PORT, Arc::new(setup.socks5), session.rt.clone()))
            );
        }
        Ok(session)
    }

    fn include_reverse_connected_service(&mut self, service: Box<dyn ReverseConnectedService>) {
        self.reverse_connected_services.insert(service.get_vsock_port(), Mutex::new(service));
    }

    /// Adds or removes listeners based on the latest listening ports from the D-Bus thread.
    fn process_update_queue(&mut self, poll_ctx: &PollContext<Token>) -> Result<()> {
        // Unwrap of LockResult is customary.
        let mut update_queue = UPDATE_QUEUE.lock().unwrap();
        let mut active_ports: BTreeSet<u16> = BTreeSet::new();

        // Add any new listeners first.
        while let Some(port) = update_queue.pop_front() {
            // Ignore privileged ports.
            if port < 1024 {
                continue;
            }
            if let BTreeMapEntry::Vacant(o) = self.listening_ports.entry(port) {
                // Failing to bind a port is not fatal, but we should log it.
                // Both IPv4 and IPv6 localhost must be bound since the host may resolve
                // "localhost" to either.
                let tcp4_listener = match TcpListener::bind((Ipv4Addr::LOCALHOST, port)) {
                    Ok(listener) => listener,
                    Err(e) => {
                        warn!("failed to bind TCPv4 port: {e}");
                        continue;
                    }
                };
                let tcp6_listener = match TcpListener::bind((Ipv6Addr::LOCALHOST, port)) {
                    Ok(listener) => listener,
                    Err(e) => {
                        warn!("failed to bind TCPv6 port: {e}");
                        continue;
                    }
                };
                poll_ctx
                    .add(&tcp4_listener, Token::Ipv4Listener(port))
                    .map_err(Error::PollContextAdd)?;
                poll_ctx
                    .add(&tcp6_listener, Token::Ipv6Listener(port))
                    .map_err(Error::PollContextAdd)?;
                o.insert(PortListeners { tcp4_listener, tcp6_listener });
                info!("forwarder_host listening to TCP4/6 port {port}");
            }
            active_ports.insert(port);
        }

        // Iterate over the existing listeners; if the port is no longer in the
        // listener list, remove it.
        let old_ports: Vec<u16> = self.listening_ports.keys().cloned().collect();
        for port in old_ports.iter() {
            if !active_ports.contains(port) {
                // Remove the PortListeners struct first - on error we want to drop it and the
                // fds it contains.
                let _listening_port = self.listening_ports.remove(port);
            }
        }
        info!("forwarder_host ports: active {active_ports:?}, listening {0:?}", self.listening_ports.keys());

        // Consume the eventfd.
        UPDATE_EVT.read().map_err(Error::UpdateEventRead)?;

        Ok(())
    }

    /// Connects to vsock and hand over to reverse-connected services
    fn process_reverse_connect_queue(&mut self, poll_ctx: &PollContext<Token>) -> Result<()> {
        // Unwrap of LockResult is customary.
        let mut reverse_connect_queue = REVCONN_QUEUE.lock().unwrap();

        // Forward connections one by one
        while let Some(port) = reverse_connect_queue.pop_front() {
            let connection = {
                let service = self.reverse_connected_services.get_mut(&port).ok_or(Error::NoRcServiceForPort(port))?
                    .get_mut().map_err(|_| Error::NoRcServiceForPort(port))?;
                let fd = hostConnectVsock(&mut self.jni_env, &self.jni_cb, port)?;
                service.on_new_connection(fd)
            };
            match connection {
                Err(e) => {
                    error!("Reverse-connected service error on accepting new connection: {e}");
                    return Err(e);
                },
                Ok(None) => {},
                Ok(Some(forwarder_session)) => { self.include_session(poll_ctx, forwarder_session, port)?; },
            }
        }

        // Consume the eventfd.
        REVCONN_EVT.read().map_err(Error::RcsEventRead)?;

        Ok(())
    }

    /// Disconnect all fd queued for disconnection
    fn process_reverse_disconnect_queue(&mut self) -> Result<()> {
        // Unwrap of LockResult is customary.
        let mut reverse_disconnect_queue = REVDISC_QUEUE.lock().unwrap();

        // Disconnect connections one by one
        while let Some(fd) = reverse_disconnect_queue.pop_front() {
            self.close_vsock_connection(fd)?;
        }

        // Consume the eventfd.
        REVDISC_EVT.read().map_err(Error::RcsEventRead)?;

        Ok(())
    }

    fn accept_connection(
        &mut self,
        poll_ctx: &PollContext<Token>,
        port: u16,
        sock_family: SocketFamily,
    ) -> Result<()> {
        info!("Incoming connection on port {port}");
        let port_listeners =
            self.listening_ports.get(&port).ok_or(Error::NoListenerForPort(port))?;

        let listener = match sock_family {
            SocketFamily::Ipv4 => &port_listeners.tcp4_listener,
            SocketFamily::Ipv6 => &port_listeners.tcp6_listener,
        };

        // This session should be dropped if any of the PollContext setup fails. Since the only
        // extant fds for the underlying sockets will be closed, they will be unregistered from
        // epoll set automatically.
        let session =
            create_forwarder_session(listener, self.cid, &mut self.jni_env, &self.jni_cb)?;
        self.include_session(poll_ctx, session, port.into())?;
        Ok(())
    }

    fn include_session(&mut self, poll_ctx: &PollContext<Token>, session: ForwarderSession, port: i32) -> Result<()> {
        let tag = session.local_stream().as_raw_fd() as u32;

        poll_ctx
            .add(session.local_stream(), Token::LocalSocket(tag))
            .map_err(Error::PollContextAdd)?;
        poll_ctx
            .add(session.remote_stream(), Token::RemoteSocket(tag))
            .map_err(Error::PollContextAdd)?;

        self.tcp4_forwarders.insert(tag, session);

        info!("Forwarder created and running for incoming connection on port {port}");
        Ok(())
    }

    fn forward_from_local(&mut self, poll_ctx: &PollContext<Token>, tag: SessionTag) -> Result<()> {
        let session = self.tcp4_forwarders.get_mut(&tag).ok_or(Error::NoSessionForTag(tag))?;
        let shutdown = session.forward_from_local(true).unwrap_or_else(|e| {
            info!("Forwarder unexpectedly shut down when forwarding local data on fd {tag}: {e}");
            true
        });
        if shutdown {
            poll_ctx.delete(session.local_stream()).map_err(Error::PollContextDelete)?;
            if session.is_shut_down() {
                let vsock_rawfd = session.remote_stream().as_raw_fd();
                self.close_vsock_connection(vsock_rawfd).ok();
                self.tcp4_forwarders.remove(&tag);
            }
        }

        Ok(())
    }

    fn forward_from_remote(
        &mut self,
        poll_ctx: &PollContext<Token>,
        tag: SessionTag,
    ) -> Result<()> {
        let session = self.tcp4_forwarders.get_mut(&tag).ok_or(Error::NoSessionForTag(tag))?;
        let shutdown = session.forward_from_remote(true).unwrap_or_else(|e| {
            info!("Forwarder unexpectedly shut down when forwarding remote data on fd {tag}: {e}");
            true
        });
        if shutdown {
            poll_ctx.delete(session.remote_stream()).map_err(Error::PollContextDelete)?;
            if session.is_shut_down() {
                let vsock_rawfd = session.remote_stream().as_raw_fd();
                self.close_vsock_connection(vsock_rawfd).ok();
                self.tcp4_forwarders.remove(&tag);
            }
        }

        Ok(())
    }

    fn close_vsock_connection(&mut self, fd: RawFd) -> Result<()> {
        hostDisconnectVsock(&mut self.jni_env, &self.jni_cb, fd)
    }

    fn run(mut self) -> Result<()> {
        let poll_ctx: PollContext<Token> = PollContext::new().map_err(Error::PollContextNew)?;
        poll_ctx.add(&*UPDATE_EVT, Token::UpdatePorts).map_err(Error::PollContextAdd)?;
        poll_ctx.add(&*REVCONN_EVT, Token::ReverseConnect).map_err(Error::PollContextAdd)?;
        poll_ctx.add(&*REVDISC_EVT, Token::ReverseDisconnect).map_err(Error::PollContextAdd)?;
        poll_ctx.add(&*SHUTDOWN_EVT, Token::Shutdown).map_err(Error::PollContextAdd)?;

        loop {
            let events = poll_ctx.wait().map_err(Error::PollWait)?;

            for event in events.iter_readable() {
                match event.token() {
                    Token::Shutdown => {
                        self.reverse_connected_services.clear();
                        match Arc::into_inner(self.rt) {
                            Some(rt) => { rt.shutdown_timeout(std::time::Duration::from_millis(500)); },
                            None => { error!("ForwarderSession shutdown dirty: tokio runtime still possessed elsewhere"); },
                        }
                        hostDisconnectVsock(&mut self.jni_env, &self.jni_cb, -1) // disconnect all
                            .inspect_err(|e| error!("ForwarderSession shutdown dirty, closing vsock error: {e}")).ok();
                        return Ok(());
                    }
                    Token::UpdatePorts => {
                        if let Err(e) = self.process_update_queue(&poll_ctx) {
                            error!("error updating listening ports: {e}");
                        }
                    }
                    Token::ReverseConnect => {
                        if let Err(e) = self.process_reverse_connect_queue(&poll_ctx) {
                            error!("error processing reverse-connecting vsock queue: {e}");
                        }
                    }
                    Token::ReverseDisconnect => {
                        if let Err(e) = self.process_reverse_disconnect_queue() {
                            error!("error processing disconnecting vsock ports: {e}");
                        }
                    }
                    Token::Ipv4Listener(port) => {
                        if let Err(e) = self.accept_connection(&poll_ctx, port, SocketFamily::Ipv4)
                        {
                            error!("error accepting connection: {e}");
                        }
                    }
                    Token::Ipv6Listener(port) => {
                        if let Err(e) = self.accept_connection(&poll_ctx, port, SocketFamily::Ipv6)
                        {
                            error!("error accepting connection: {e}");
                        }
                    }
                    Token::LocalSocket(tag) => {
                        if let Err(e) = self.forward_from_local(&poll_ctx, tag) {
                            error!("error forwarding local traffic: {e}");
                        }
                    }
                    Token::RemoteSocket(tag) => {
                        if let Err(e) = self.forward_from_remote(&poll_ctx, tag) {
                            error!("error forwarding remote traffic: {e}");
                        }
                    }
                }
            }
        }
    }
}

/// Call hostConnectVsock(Int) through JNI
#[allow(non_snake_case)]
fn hostConnectVsock(
    jni_env: &mut JNIEnv,
    jni_cb: &JObject,
    vsock_port: i32,
) -> Result<RawFd> {
    Ok(
        jni_env.call_method(
            jni_cb,
            "hostConnectVsock",
            "(I)I",
            &[JValue::Int(vsock_port)],
        )
        .map_err(Error::VsockConnect)?
        .i().map_err(Error::VsockConnect)?
        as RawFd
    )
}

/// Call hostDisconnectVsock(Int) through JNI
#[allow(non_snake_case)]
fn hostDisconnectVsock(
    jni_env: &mut JNIEnv,
    jni_cb: &JObject,
    fd: RawFd,
) -> Result<()> {
    jni_env
        .call_method(
            jni_cb,
            "hostDisconnectVsock",
            "(I)V",
            #[allow(clippy::unnecessary_cast)]
            &[JValue::Int(fd as i32)],
        )
        .map_err(Error::VsockDisconnect)?;
    Ok(())
}

/// Creates a forwarder session from a `listener` that has a pending connection to accept.
fn create_forwarder_session(
    listener: &TcpListener,
    _cid: u32,
    jni_env: &mut JNIEnv,
    jni_cb: &JObject,
) -> Result<ForwarderSession> {
    let (tcp_stream, _) = listener.accept().map_err(Error::TcpAccept)?;
    // Cannot bind vsock ports due to permission issues. Use callback to obtain connection instead.
    // Note that the original implementation is host=initiate + guest=connect,
    // while using connectVsock() can only give you host=connect + guest=initiate.
    // Therefore, a different guest/linux_vm_manager implementation is needed to switch from
    //   socat VSOCK-CONNECT...
    // to
    //   socat VSOCK-LISTEN...

    let tcp4_port = listener.local_addr().map_err(Error::TcpListenerPort)?.port();
    let vsock_port = tcp4_port;

    // Ask guest to connect.
    info!("forwarder_host setting up guest connection: VSOCK-LISTEN ({vsock_port}) > TCP-CONNECT ({tcp4_port})");
    jni_env
        .call_method(
            jni_cb,
            "onForwardingRequestReceived",
            "(II)V",
            &[JValue::Int(tcp4_port as i32), JValue::Int(vsock_port as i32)],
        )
        .map_err(Error::VsockConnect)?;
    // brief wait for socat to come online
    std::thread::sleep(std::time::Duration::from_millis(100));

    // Connect from host and assume that the guest is listening on the vsock.
    info!("forwarder_host setting up connection: TCP-LISTEN ({tcp4_port}) > VSOCK-CONNECT ({vsock_port})");
    let vsock_rawfd = hostConnectVsock(jni_env, jni_cb, vsock_port.into())?;

    // The original workflow of listening for vsock connection does not apply.
    // Simply try connecting the vsock.
    // Safety: fd only used in rust, and closing in jni is called when done, and specified not handling closing
    let mut vsock_stream = unsafe { StreamSocket::from_raw_fd(vsock_rawfd) };
    vsock_stream.handles_closing = false;
    Ok(ForwarderSession::new(tcp_stream.into(), vsock_stream))
}

#[allow(non_snake_case)]
#[derive(Debug)]
struct Socks5Setup {
    delegated: u16, // =0 for managed, >0 for 3rd-party port
    loopback: bool,
    udp: bool,
    multicast: bool,
    timeout: std::time::Duration,
}

impl Socks5Setup {
    pub fn new(delegated: u16, loopback: bool, udp: bool, multicast: bool, timeout_ms: u64) -> Self {
        Socks5Setup { delegated, loopback, udp, multicast, timeout: std::time::Duration::from_millis(timeout_ms) }
    }

    pub fn allows_address(&self, addr: &IpAddr) -> bool {
        if addr.is_loopback() && !self.loopback {
            return false;
        }
        if addr.is_multicast() && !self.multicast {
            return false;
        }
        if let IpAddr::V4(addr) = addr {
            if addr.is_broadcast() && !self.multicast {
                return false;
            }
        }
        true
    }
}

#[allow(non_snake_case)]
#[derive(Debug)]
struct ForwarderHostSetup {
    rcServices: Vec<i32>,
    socks5: Socks5Setup,
    REVCONN_SOCKS5_PROXY: i32,
    SOCKS5_PORT: i32,
}

impl ForwarderHostSetup {
    fn get_field<'a, 'b>(jni_env: &mut JNIEnv<'a>, obj: &JObject<'b>, field: &str, ty: &str) -> result::Result<JValueOwned<'a>, jni::errors::Error> {
        jni_env.get_field(obj, field, ty).inspect_err(|e| error!("failed to get field {field}: {e}"))
    }

    fn get_field_int_array(jni_env: &mut JNIEnv, obj: &JObject, field: &str) -> result::Result<Vec<i32>, jni::errors::Error> {
        let array = Self::get_field(jni_env, obj, field, "[I")?
            .l().inspect_err(|e| error!("failed to cast JValueOwned -> JObject: {e}"))?;
        let int_array = JIntArray::from(array);
        let len = jni_env
            .get_array_length(&int_array)
            .inspect_err(|e| error!("failed to get array length: {e}"))?;
        let mut values = vec![0i32; len as usize];
        jni_env
            .get_int_array_region(&int_array, 0, &mut values)
            .inspect_err(|e| error!("failed to copy IntArray contents: {e}"))?;
        Ok(values)
    }

    fn get_field_int(jni_env: &mut JNIEnv, obj: &JObject, field: &str) -> result::Result<i32, jni::errors::Error> {
        Self::get_field(jni_env, obj, field, "I")?.i().inspect_err(|e| error!("failed to cast field {field} as int: {e}"))
    }

    fn get_field_bool(jni_env: &mut JNIEnv, obj: &JObject, field: &str) -> result::Result<bool, jni::errors::Error> {
        Self::get_field(jni_env, obj, field, "Z")?.z().inspect_err(|e| error!("failed to cast field {field} as int: {e}"))
    }

    fn from_java(jni_env: &mut JNIEnv, setup: JObject) -> result::Result<ForwarderHostSetup, jni::errors::Error> {
        let socks5 = Self::get_field(jni_env, &setup, "socks5", "Lcom/android/virtualization/koiterminal/Socks5Setup;")?.l().inspect_err(|e| error!("failed to cast field socks5 as object: {e}"))?;
        Ok(ForwarderHostSetup {
            rcServices: Self::get_field_int_array(jni_env, &setup, "rcServices")?,
            socks5: Socks5Setup::new(
                Self::get_field_int(jni_env, &socks5, "delegated")?.try_into().unwrap_or(0),
                Self::get_field_bool(jni_env, &socks5, "loopback")?,
                Self::get_field_bool(jni_env, &socks5, "udp")?,
                Self::get_field_bool(jni_env, &socks5, "multicast")?,
                Self::get_field_int(jni_env, &socks5, "timeout_ms")?.try_into().unwrap_or(0),
            ),
            REVCONN_SOCKS5_PROXY: Self::get_field_int(jni_env, &setup, "REVCONN_SOCKS5_PROXY")?,
            SOCKS5_PORT: Self::get_field_int(jni_env, &setup, "SOCKS5_PORT")?,
        })
    }
}

fn run_forwarder_host(cid: i32, jni_env: JNIEnv, setup: JObject, jni_cb: JObject) -> Result<()> {
    debug!("Starting forwarder_host");
    let sessions = ForwarderSessions::new(cid, jni_env, setup, jni_cb)?;
    sessions.run()
}

/// JNI function for running forwarder_host.
#[no_mangle]
pub extern "C" fn Java_com_android_virtualization_terminal_ForwarderHost_run(
    env: JNIEnv,
    _clazz: JClass,
    cid: jint,
    setup: JObject,
    callback: JObject,
) {
    android_logger::init_once(
        Config::default().with_max_level(LevelFilter::Debug).with_tag("ForwarderHost"),
    );

    // Clear shutdown event FD before running forwarder host.
    SHUTDOWN_EVT.write(1).expect("Failed to write shutdown event FD");
    SHUTDOWN_EVT.read().expect("Failed to consume shutdown event FD");

    match run_forwarder_host(cid, env, setup, callback) {
        Ok(_) => {
            info!("forwarder_host is terminated");
        }
        Err(e) => {
            error!("Error on forwarder_host: {e:?}");
        }
    }
}
/// Renamed. See above
#[no_mangle]
pub extern "C" fn Java_com_android_virtualization_koiterminal_ForwarderHost_run(
    env: JNIEnv,
    _clazz: JClass,
    cid: jint,
    setup: JObject,
    callback: JObject,
) {
    Java_com_android_virtualization_terminal_ForwarderHost_run(env, _clazz, cid, setup, callback);
}

/// JNI function for terminating forwarder_host.
#[no_mangle]
pub extern "C" fn Java_com_android_virtualization_terminal_ForwarderHost_shutdown(
    _env: JNIEnv,
    _clazz: JClass,
) {
    SHUTDOWN_EVT.write(1).expect("Failed to write shutdown event FD");
}
/// Renamed. See above
#[no_mangle]
pub extern "C" fn Java_com_android_virtualization_koiterminal_ForwarderHost_shutdown(
    _env: JNIEnv,
    _clazz: JClass,
) {
    Java_com_android_virtualization_terminal_ForwarderHost_shutdown(_env, _clazz);
}

/// JNI function for updating listening ports.
#[no_mangle]
pub extern "C" fn Java_com_android_virtualization_terminal_ForwarderHost_updateListeningPorts(
    env: JNIEnv,
    _clazz: JClass,
    ports: JIntArray,
) {
    info!("Java_com_android_virtualization_terminal_ForwarderHost_updateListeningPorts");
    let length = env.get_array_length(&ports).expect("Failed to get length of port array");
    let mut buf = vec![0; length as usize];
    env.get_int_array_region(ports, 0, &mut buf).expect("Failed to get port array");

    let mut update_queue = UPDATE_QUEUE.lock().unwrap();
    update_queue.clear();
    for port in buf {
        update_queue.push_back(port.try_into().expect("Failed to add port into update queue"));
    }
    UPDATE_EVT.write(1).expect("failed to write update eventfd");
}
/// Renamed. See above
#[no_mangle]
pub extern "C" fn Java_com_android_virtualization_koiterminal_ForwarderHost_updateListeningPorts(
    env: JNIEnv,
    _clazz: JClass,
    ports: JIntArray,
) {
    Java_com_android_virtualization_terminal_ForwarderHost_updateListeningPorts(env, _clazz, ports);
}

/// JNI function for request connection from reverse-connected services.
/// fun requestReverseConnection(vsockPort: Int)
#[no_mangle]
pub extern "C" fn Java_com_android_virtualization_koiterminal_ForwarderHost_requestReverseConnection(
    _env: JNIEnv,
    _clazz: JClass,
    vsock_port: jint,
) {
    let mut revconn_queue = REVCONN_QUEUE.lock().unwrap();
    revconn_queue.push_back(vsock_port);
    REVCONN_EVT.write(1).expect("failed to write reverse-connect eventfd");
}

