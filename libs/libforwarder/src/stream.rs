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
// src/platform2/vm_tools/chunnel/src/stream.rs

//! This module provides abstraction of various stream socket type.

use std::fmt;
use std::io;
use std::net::TcpStream;
use std::os::fd::BorrowedFd;
use std::os::unix::io::{AsRawFd, FromRawFd, IntoRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::pin::Pin;
use std::task::{Context, Poll, ready};
use std::result;
use crate::forwarder::MAX_FRAME_SIZE;

use libc::{self, c_void, shutdown, EPIPE, SHUT_WR};
#[cfg(debug_assertions)]
use log::debug;
use log::{info, error};
use tokio::{io::{AsyncRead, AsyncWrite, ReadBuf, unix::AsyncFd}, task};
use vsock::VsockAddr;
use vsock::VsockStream;

/// Parse a vsock SocketAddr from a string. vsock socket addresses are of the form
/// "vsock:cid:port".
pub fn parse_vsock_addr(addr: &str) -> io::Result<VsockAddr> {
    let components: Vec<&str> = addr.split(':').collect();
    if components.len() != 3 || components[0] != "vsock" {
        return Err(io::Error::from_raw_os_error(libc::EINVAL));
    }

    Ok(VsockAddr::new(
        components[1].parse().map_err(|_| io::Error::from_raw_os_error(libc::EINVAL))?,
        components[2].parse().map_err(|_| io::Error::from_raw_os_error(libc::EINVAL))?,
    ))
}

/// StreamSocket provides a generic abstraction around any connection-oriented stream socket.
/// The socket will be closed when StreamSocket is dropped, but writes to the socket can also
/// be shut down manually.
pub struct StreamSocket {
    fd: RawFd,
    shut_down: bool,
    /// Whether this struct should close fd on drop.
    pub handles_closing: bool,
}

impl StreamSocket {
    /// Connects to the given socket address. Supported socket types are vsock, unix, and TCP.
    pub fn connect(sockaddr: &str) -> result::Result<StreamSocket, StreamSocketError> {
        const UNIX_PREFIX: &str = "unix:";
        const VSOCK_PREFIX: &str = "vsock:";

        if sockaddr.starts_with(VSOCK_PREFIX) {
            let addr = parse_vsock_addr(sockaddr)
                .map_err(|e| StreamSocketError::ConnectVsock(sockaddr.to_string(), e))?;
            let vsock_stream = VsockStream::connect(&addr)
                .map_err(|e| StreamSocketError::ConnectVsock(sockaddr.to_string(), e))?;
            Ok(vsock_stream.into())
        } else if sockaddr.starts_with(UNIX_PREFIX) {
            let (_prefix, sock_path) = sockaddr.split_at(UNIX_PREFIX.len());
            let unix_stream = UnixStream::connect(sock_path)
                .map_err(|e| StreamSocketError::ConnectUnix(sockaddr.to_string(), e))?;
            Ok(unix_stream.into())
        } else {
            // Assume this is a TCP stream.
            let tcp_stream = TcpStream::connect(sockaddr)
                .map_err(|e| StreamSocketError::ConnectTcp(sockaddr.to_string(), e))?;
            Ok(tcp_stream.into())
        }
    }

    /// Shuts down writes to the socket using shutdown(2).
    pub fn shut_down_write(&mut self) -> io::Result<()> {
        // SAFETY:
        // Safe because no memory is modified and the return value is checked.
        let ret = unsafe { shutdown(self.fd, SHUT_WR) };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }

        self.shut_down = true;
        Ok(())
    }

    /// Returns true if the socket has been shut down for writes, false otherwise.
    pub fn is_shut_down(&self) -> bool {
        self.shut_down
    }
}

impl io::Read for StreamSocket {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        // SAFETY:
        // Safe because this will only modify the contents of |buf| and we check the return value.
        let ret = unsafe { libc::read(self.fd, buf.as_mut_ptr() as *mut c_void, buf.len()) };
        if ret < 0 {
            return Err(io::Error::last_os_error());
        }

        Ok(ret as usize)
    }
}

impl io::Write for StreamSocket {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        // SAFETY:
        // Safe because this doesn't modify any memory and we check the return value.
        let ret = unsafe { libc::write(self.fd, buf.as_ptr() as *const c_void, buf.len()) };
        if ret < 0 {
            // If a write causes EPIPE then the socket is shut down for writes.
            let err = io::Error::last_os_error();
            if let Some(errno) = err.raw_os_error() {
                if errno == EPIPE {
                    self.shut_down = true
                }
            }

            return Err(err);
        }

        Ok(ret as usize)
    }

    fn flush(&mut self) -> io::Result<()> {
        // No buffered data so nothing to do.
        Ok(())
    }
}

impl AsRawFd for StreamSocket {
    fn as_raw_fd(&self) -> RawFd {
        self.fd
    }
}

impl From<TcpStream> for StreamSocket {
    fn from(stream: TcpStream) -> Self {
        StreamSocket { fd: stream.into_raw_fd(), shut_down: false, handles_closing: true }
    }
}

impl From<UnixStream> for StreamSocket {
    fn from(stream: UnixStream) -> Self {
        StreamSocket { fd: stream.into_raw_fd(), shut_down: false, handles_closing: true }
    }
}

impl From<VsockStream> for StreamSocket {
    fn from(stream: VsockStream) -> Self {
        StreamSocket { fd: stream.into_raw_fd(), shut_down: false, handles_closing: true }
    }
}

impl FromRawFd for StreamSocket {
    unsafe fn from_raw_fd(fd: RawFd) -> Self {
        StreamSocket { fd, shut_down: false, handles_closing: true }
    }
}

impl Drop for StreamSocket {
    fn drop(&mut self) {
        if !self.handles_closing {
            return;
        }
        // SAFETY:
        // Safe because this doesn't modify any memory and we are the only
        // owner of the file descriptor.
        unsafe { libc::close(self.fd) };
    }
}

/// Error enums for StreamSocket.
#[remain::sorted]
#[derive(Debug)]
pub enum StreamSocketError {
    /// Error on connecting TCP socket.
    ConnectTcp(String, io::Error),
    /// Error on connecting unix socket.
    ConnectUnix(String, io::Error),
    /// Error on connecting vsock socket.
    ConnectVsock(String, io::Error),
}

/// Implement tokio-compatible async operations for file descriptors owned by others.
/// Refrains from closing the fd, but can shutdown writes.
/// (mainly vsock, either from vm.connectVsock() or vsock::*)
pub struct AsyncBorrowedFdStream<'a> {
    fd: AsyncFd<BorrowedFd<'a>>,
    shut_down: bool,
}

impl<'a> AsyncBorrowedFdStream<'a> {
    /// Creates from anything that gives you a raw fd.
    /// # Safety
    /// Safe if during my lifetime fd is not read by others when I read,
    /// or written to / shut down by others when I write to it / shut it down.
    pub unsafe fn new<T: AsRawFd>(fd: &T) -> io::Result<Self> {
        let fd = fd.as_raw_fd();
        // Safety: safe if fd is not operated on by others during my lifetime
        let fd = AsyncFd::new(unsafe {
            let result = BorrowedFd::borrow_raw(fd);
            result
        }).inspect_err(|e| error!("AsyncBorrowedFdStream: AsyncFd::new() error {e}"))?;
        Ok(Self { fd, shut_down: false })
    }

    /// Helper to print bytes sent/received
    #[cfg(debug_assertions)]
    fn to_hex_or_http_string(buf: &[u8], len: isize) -> String {
        if let Ok(content) = String::from_utf8(buf[..len as usize].to_vec()) {
            if content.starts_with("HTTP") {
                return "HTTP content:\n".to_owned() + &content;
            }
        }
        // Safety: will panic if len < 0 and buf.len() < 16, caution if used outside this module!
        buf[..std::cmp::min(len as usize, 16)].iter().map(|b| format!("{:02X?}", b)).collect::<Vec<String>>().join(" ")
    }

    /// Shutdown write
    pub fn raw_shutdown(fd: RawFd, shut_down: &mut bool) -> io::Result<()> {
        // SAFETY:
        // Safe because no memory is modified and the return value is checked.
        let ret = unsafe { shutdown(fd, SHUT_WR) };
        if ret < 0 {
            info!("AsyncBorrowedFdStream: shutdown error ret={ret}");
            return Err(io::Error::last_os_error());
        }
        *shut_down = true;
        Ok(())
    }

    /// Helper to read from raw fd. Copied from StreamSocket
    fn raw_read(fd: RawFd, out: &mut [u8]) -> io::Result<usize> {
        // SAFETY:
        // Safe because this will only modify the contents of |out| and we check the return value.
        let ret = unsafe { libc::read(fd, out.as_mut_ptr() as *mut c_void, out.len()) };
        if ret < 0 {
            let err = io::Error::last_os_error();
            if let Some(errno) = err.raw_os_error() {
                info!("AsyncBorrowedFdStream: forwarding raw_read os errorno {errno}, inside:");
            }
            info!("AsyncBorrowedFdStream: forwarding raw_read error {err}");
            return Err(err);
        }
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: forwarding raw_read ({ret}) {}", Self::to_hex_or_http_string(out, ret));
        Ok(ret as usize)
    }

    /// Helper to write to raw fd. Copied from StreamSocket
    fn raw_write(fd: RawFd, buf: &[u8], shut_down: &mut bool) -> io::Result<usize> {
        // SAFETY:
        // Safe because this doesn't modify any memory and we check the return value.
        let ret = unsafe { libc::write(fd, buf.as_ptr() as *const c_void, buf.len()) };
        if ret < 0 {
            // If a write causes EPIPE then the socket is shut down for writes.
            let err = io::Error::last_os_error();
            if let Some(errno) = err.raw_os_error() {
                if errno == EPIPE {
                    *shut_down = true;
                }
                info!("AsyncBorrowedFdStream: forwarding raw_write os errorno {errno}, inside:");
            }
            info!("AsyncBorrowedFdStream: forwarding raw_write error {err}");
            return Err(err);
        }
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: forwarding raw_write ({ret}) {}", Self::to_hex_or_http_string(buf, ret));
        Ok(ret as usize)
    }

    /// Helper for write_all. Copied from std::io.
    fn raw_write_all(fd: RawFd, mut buf: &[u8], shut_down: &mut bool) -> io::Result<()> {
        while !buf.is_empty() {
            match Self::raw_write(fd, buf, shut_down) {
                Ok(0) => {
                    *shut_down = true;
                    return Ok(());
                }
                Ok(n) => buf = &buf[n..],
                // Err(ref e) if e.is_interrupted() => {} // no access, we can just crash
                Err(e) => return Err(e),
            }
        }
        Ok(())
    }
}

/// This is mostly copied from https://docs.rs/tokio/latest/tokio/io/unix/struct.AsyncFd.html, and its license is MIT
impl<'a> AsyncRead for AsyncBorrowedFdStream<'a> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>
    ) -> Poll<io::Result<()>> {
        loop {
            let mut guard = ready!({
                self.fd.poll_read_ready(cx)
            })?;

            let unfilled = buf.initialize_unfilled();
            match guard.try_io(|inner| Self::raw_read(inner.as_raw_fd(), unfilled)) {
                Ok(Ok(len)) => {
                    buf.advance(len);
                    return Poll::Ready(Ok(()));
                },
                Ok(Err(err)) => {
                    info!("AsyncBorrowedFdStream: forwarding poll_read Ok(Err({err}))");
                    return Poll::Ready(Err(err));
                },
                Err(_would_block) => {
                    continue;
                },
            }
        }
    }
}

impl<'a> AsyncWrite for AsyncBorrowedFdStream<'a> {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8]
    ) -> Poll<io::Result<usize>> {
        loop {
            let mut guard = ready!(self.fd.poll_write_ready(cx))?;
            let mut shut_down = self.shut_down;

            match guard.try_io(|inner| Self::raw_write(inner.as_raw_fd(), buf, &mut shut_down)) {
                Ok(result) => {
                    self.shut_down = shut_down;
                    return Poll::Ready(result);
                },
                Err(_would_block) => {
                    self.shut_down = shut_down;
                    continue;
                },
            }
        }
    }

    fn poll_flush(
        self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
    ) -> Poll<io::Result<()>> {
        // flush is a no-op
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(
        mut self: Pin<&mut Self>,
        _cx: &mut Context<'_>,
    ) -> Poll<io::Result<()>> {
        Poll::Ready(Self::raw_shutdown(self.fd.as_raw_fd(), &mut self.shut_down))
    }
}

impl<'a> AsRawFd for AsyncBorrowedFdStream<'a> {
    fn as_raw_fd(&self) -> RawFd {
        self.fd.as_raw_fd()
    }
}

/// Stream forwarding direction
#[derive(Clone, Copy, Debug)]
pub enum FwdDirection {
    /// local -> remote
    Fwd,
    /// remote -> local
    Bwd,
}

/// Another forwarder session implementation based on tokio instead of polling (which does not work on connectVsock() file descriptors)
///
/// After failed attempts to implement these as AsyncWrite and AsyncRead, 
/// which cannot be done due to lack of support of setting non-block to file descriptors and ioctl check buffer sbytes,
/// now these are implemented as blocking and uses tokio::task::spawn_blocking which are glorified threads. Two of these per connection.
/// Note: due to how AsyncFd works, this class requires that any tokio::net::TcpStream be converted to std::net::TcpStream,
/// and set everything to blocking before passing its RawFd.
/// It also does not use the async implementations in AsyncBorrowedFdStream (only the raw_* ones).
pub struct AsyncForwarderSession {
    /// local (e.g. vsock)
    pub local: RawFd,
    /// remote (e.g. tcp)
    pub remote: RawFd,
    /// variables that keep connection alive, droppable when self is dropped
    pub _drop_closer_v: Option<VsockStream>,
    /// variables that keep connection alive, droppable when self is dropped
    pub _drop_closer_t: Option<std::net::TcpStream>,
    /// Associated port for forwarding, used for logging
    pub port: u16,
}

impl AsyncForwarderSession {
    /// Constructor
    pub fn new(local: RawFd, remote: RawFd, _drop_closer_v: Option<VsockStream>, _drop_closer_t: Option<std::net::TcpStream>, port: u16) -> Self {
        AsyncForwarderSession { local, remote, _drop_closer_v, _drop_closer_t, port }
    }

    fn forward_once(src: RawFd, tgt: RawFd, _dir: FwdDirection, shut_down: &mut bool) -> io::Result<bool> {
        let mut buf = [0u8; MAX_FRAME_SIZE];
        let size = AsyncBorrowedFdStream::raw_read(src, &mut buf)?;
        if size == 0 {
            return Ok(true);
        }
        AsyncBorrowedFdStream::raw_write_all(tgt, &buf[..size], shut_down)?;
        Ok(false)
    }

    fn run_oneway_try(
        port: u16, src: RawFd, tgt: RawFd, dir: FwdDirection, shut_down: &mut bool
    ) -> io::Result<()> {
        loop {
            match Self::forward_once(src, tgt, dir, shut_down) {
                Ok(shutdown) => if shutdown { return Ok(()); },
                Err(e) => { error!("Failed in {dir:?} run_oneway_try for port {}: {e}", port); return Err(e); }
            }
        }
    }

    fn run_oneway(
        port: u16, src: RawFd, tgt: RawFd, dir: FwdDirection
    ) -> io::Result<()> {
        let mut shut_down = false;
        let result = Self::run_oneway_try(port, src, tgt, dir, &mut shut_down);
        AsyncBorrowedFdStream::raw_shutdown(tgt, &mut shut_down)
            .inspect_err(|e| error!("Forwarding port {} {dir:?} teardown: {e}", port)).ok();
        result
    }

    /// Run forwarding with tokio::task::spawn_blocking
    pub async fn run(me: Self) -> io::Result<()> {
        let (local, remote, port) = (me.local, me.remote, me.port);
        let fw = task::spawn_blocking(move || AsyncForwarderSession::run_oneway(port, local, remote, FwdDirection::Fwd));
        let (local, remote, port) = (me.local, me.remote, me.port);
        let bw = task::spawn_blocking(move || AsyncForwarderSession::run_oneway(port, remote, local, FwdDirection::Bwd));
        let (fw, bw) = (fw.await, bw.await);
        let port = me.port;
        match fw {
            Err(ref e) => { error!("Forwarding session (fwd) on port {} aborted: {e}", port); },
            Ok(Err(ref e)) => { error!("Forwarding session (fwd) on port {} failed: {e}", port); },
            _ => {},
        }
        match bw {
            Err(ref e) => { error!("Forwarding session (bwd) on port {} aborted: {e}", port); },
            Ok(Err(ref e)) => { error!("Forwarding session (bwd) on port {} failed: {e}", port); },
            _ => {},
        }
        fw??;
        bw??;
        Ok(())
    }
}

impl fmt::Display for StreamSocketError {
    #[remain::check]
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        use self::StreamSocketError::*;

        #[remain::sorted]
        match self {
            ConnectTcp(sockaddr, e) => {
                write!(f, "failed to connect to TCP sockaddr {sockaddr}: {e}")
            }
            ConnectUnix(sockaddr, e) => {
                write!(f, "failed to connect to unix sockaddr {sockaddr}: {e}")
            }
            ConnectVsock(sockaddr, e) => {
                write!(f, "failed to connect to vsock sockaddr {sockaddr}: {e}")
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::os::unix::net::{UnixListener, UnixStream};
    use tempfile::TempDir;

    #[test]
    fn sock_connect_tcp() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let sockaddr = format!("127.0.0.1:{}", listener.local_addr().unwrap().port());

        let _stream = StreamSocket::connect(&sockaddr).unwrap();
    }

    #[test]
    fn sock_connect_unix() {
        let tempdir = TempDir::new().unwrap();
        let path = tempdir.path().to_owned().join("test.sock");
        let _listener = UnixListener::bind(&path).unwrap();

        let unix_addr = format!("unix:{}", path.to_str().unwrap());
        let _stream = StreamSocket::connect(&unix_addr).unwrap();
    }

    #[test]
    fn invalid_sockaddr() {
        assert!(StreamSocket::connect("this is not a valid sockaddr").is_err());
    }

    #[test]
    fn shut_down_write() {
        let (unix_stream, _dummy) = UnixStream::pair().unwrap();
        let mut stream: StreamSocket = unix_stream.into();

        stream.write_all(b"hello").unwrap();

        stream.shut_down_write().unwrap();

        assert!(stream.is_shut_down());
        assert!(stream.write(b"goodbye").is_err());
    }

    #[test]
    fn read_from_shut_down_sock() {
        let (unix_stream1, unix_stream2) = UnixStream::pair().unwrap();
        let mut stream1: StreamSocket = unix_stream1.into();
        let mut stream2: StreamSocket = unix_stream2.into();

        stream1.shut_down_write().unwrap();

        // Reads from the other end of the socket should now return EOF.
        let mut buf = Vec::new();
        assert_eq!(stream2.read_to_end(&mut buf).unwrap(), 0);
    }
}
