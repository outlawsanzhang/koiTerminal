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
use std::os::unix::io::{AsRawFd, FromRawFd, IntoRawFd, RawFd};
use std::os::unix::net::UnixStream;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};
use std::result;
use crate::forwarder::MAX_FRAME_SIZE;

use libc::{self, c_void, shutdown, EPIPE, SHUT_WR};
#[cfg(debug_assertions)]
use log::debug;
use log::{info, error};
use tokio::{io::{AsyncRead, AsyncWrite, ReadBuf}, task};
use tokio::runtime::Runtime;
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

    /// Force mark shut down in case of permission issues
    pub fn mark_shut_down(&mut self) {
        self.shut_down = true;
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
pub struct AsyncBorrowedFdStream {
    fd: RawFd,
    read_lock: Arc<Mutex<(Option<io::Result<usize>>, [u8; MAX_FRAME_SIZE], usize)>>, // read result, read buffer, buffer offset
    read_wakers: Arc<Mutex<Vec<Waker>>>,
    write_lock: Arc<Mutex<(Option<io::Result<()>>, [u8; MAX_FRAME_SIZE], usize, bool)>>, // write result, write buffer, write buffer length, shut_down
    write_wakers: Arc<Mutex<Vec<Waker>>>, // write, flush
    shutdown_lock: Arc<Mutex<Option<io::Result<()>>>>, // write result, write buffer, write buffer length, shut_down
    shutdown_wakers: Arc<Mutex<Vec<Waker>>>,
    rt: Arc<Runtime>,
    shut_down: Mutex<bool>,
    skips_shut_down: bool,
}

impl AsyncBorrowedFdStream {
    /// Creates from anything that gives you a raw fd.
    /// # Safety
    /// Safe if during my lifetime fd is not read by others when I read,
    /// or written to / shut down by others when I write to it / shut it down.
    pub unsafe fn new<T: AsRawFd>(fd: &T, rt: Arc<Runtime>, skips_shut_down: bool) -> Self {
        // Safety: safe if fd is not operated on by others during my lifetime
        let fd = fd.as_raw_fd();
        let result = Self {
            fd,
            read_lock: Arc::new(Mutex::new((None, [0u8; MAX_FRAME_SIZE], 0usize))), // start = not ready to read
            read_wakers: Arc::new(Mutex::new(Vec::new())),
            write_lock: Arc::new(Mutex::new((Some(Ok(())), [0u8; MAX_FRAME_SIZE], 0usize, false))), // start = ready to write
            write_wakers: Arc::new(Mutex::new(Vec::new())),
            shutdown_lock: Arc::new(Mutex::new(None)), // start = ready to shut down
            shutdown_wakers: Arc::new(Mutex::new(Vec::new())),
            rt: rt.clone(),
            shut_down: Mutex::new(false),
            skips_shut_down,
        };
        result.read_in_bg();
        info!("AsyncBorrowedFdStream: Launched new stream fd={fd}; total tasks {}", rt.metrics().num_alive_tasks());
        result
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
    pub fn raw_shutdown(fd: RawFd) -> io::Result<()> {
        // SAFETY:
        // Safe because no memory is modified and the return value is checked.
        let ret = unsafe { shutdown(fd, SHUT_WR) };
        if ret < 0 {
            info!("AsyncBorrowedFdStream: shutdown error ret={ret}");
            return Err(io::Error::last_os_error());
        }
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
    fn raw_write(fd: RawFd, buf: &[u8]) -> (io::Result<usize>, bool) {
        let mut shut_down = false;
        // SAFETY:
        // Safe because this doesn't modify any memory and we check the return value.
        let ret = unsafe { libc::write(fd, buf.as_ptr() as *const c_void, buf.len()) };
        if ret < 0 {
            // If a write causes EPIPE then the socket is shut down for writes.
            let err = io::Error::last_os_error();
            if let Some(errno) = err.raw_os_error() {
                if errno == EPIPE {
                    shut_down = true;
                }
                info!("AsyncBorrowedFdStream: forwarding raw_write os errorno {errno}, inside:");
            }
            info!("AsyncBorrowedFdStream: forwarding raw_write error {err}");
            return (Err(err), shut_down);
        }
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: forwarding raw_write ({ret}) {}", Self::to_hex_or_http_string(buf, ret));
        (Ok(ret as usize), shut_down)
    }

    /// Helper for write_all. Copied from std::io.
    fn raw_write_all(fd: RawFd, buf: &[u8]) -> (io::Result<()>, bool) {
        let mut shut_down = false;
        let mut buf = buf;
        while !buf.is_empty() {
            let (result, sd) = Self::raw_write(fd, buf);
            shut_down |= sd;
            match result {
                Ok(0) => {
                    shut_down = true;
                    return (Ok(()), shut_down);
                }
                Ok(n) => buf = &buf[n..],
                // Err(ref e) if e.is_interrupted() => {} // no access, we can just crash
                Err(e) => return (Err(e), shut_down),
            }
        }
        (Ok(()), shut_down)
    }

    /// Helper for reading in background
    fn read_in_bg(&self) {
        let fd = self.fd;
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: fd={fd} launching next read_in_bg");
        let read_lock = self.read_lock.clone();
        let wakers = self.read_wakers.clone();
        self.rt.spawn_blocking(move || {
            #[cfg(debug_assertions)]
            debug!("AsyncBorrowedFdStream: fd={fd} read_in_bg");
            let mut result0_buf1_off2 = read_lock.lock().unwrap();
            if result0_buf1_off2.0.is_none() {
                // read in background; IO is blocking
                result0_buf1_off2.0 = Some(Self::raw_read(fd, &mut result0_buf1_off2.1));
                result0_buf1_off2.2 = 0usize;
                let mut wakers = wakers.lock().unwrap();
                wakers.drain(..).for_each(|w| w.wake());
                // Drop wakers last, and let fg lock wakers first.
                // This guarantees that if fg locks wakers but fails to lock buffer (and assumes that bg is IO-blocked),
                // the bg work is actually IO-blocked (and therefore the wakers it passes to bg WILL be checked)
                // instead of being in the teardown process (passed wakers will be ignored).
                #[cfg(debug_assertions)]
                debug!("AsyncBorrowedFdStream: fd={fd} read_in_bg done ({:?}), dropping locks", result0_buf1_off2.0);
                std::mem::drop(result0_buf1_off2);
                std::mem::drop(wakers);
            }
        });
    }

    /// Helper for writing in background
    fn write_in_bg(&self) {
        let fd = self.fd;
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: fd={fd} launching next write_in_bg");
        let write_lock = self.write_lock.clone();
        let wakers = self.write_wakers.clone();
        self.rt.spawn_blocking(move || {
            #[cfg(debug_assertions)]
            debug!("AsyncBorrowedFdStream: fd={fd} write_in_bg");
            let mut result0_buf1_len2_sd3 = write_lock.lock().unwrap();
            if result0_buf1_len2_sd3.0.is_none() { // if there are things to write
                let write_result = Self::raw_write_all(fd, &result0_buf1_len2_sd3.1[..result0_buf1_len2_sd3.2]);
                (result0_buf1_len2_sd3.0, result0_buf1_len2_sd3.3) = (Some(write_result.0), write_result.1);
                let mut wakers = wakers.lock().unwrap();
                wakers.drain(..).for_each(|w| w.wake());
                // Drop wakers last, and let fg lock wakers first.
                // This guarantees that if fg locks wakers but fails to lock buffer (and assumes that bg is IO-blocked),
                // the bg work is actually IO-blocked (and therefore the wakers it passes to bg WILL be checked)
                // instead of being in the teardown process (passed wakers will be ignored).
                #[cfg(debug_assertions)]
                debug!("AsyncBorrowedFdStream: fd={fd} write_in_bg done ({:?}), dropping locks", result0_buf1_len2_sd3.0);
                std::mem::drop(result0_buf1_len2_sd3);
                std::mem::drop(wakers);
            }
        });
    }

    /// Helper for shutting down in background
    fn shutdown_in_bg(&self) {
        let fd = self.fd;
        if *self.shut_down.lock().unwrap() {
            info!("AsyncBorrowedFdStream: fd={fd} skipping shutdown_in_bg: already shut down");
            return;
        }
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: fd={fd} launching shutdown_in_bg");
        let shutdown_lock = self.shutdown_lock.clone();
        let wakers = self.shutdown_wakers.clone();
        self.rt.spawn_blocking(move || {
            #[cfg(debug_assertions)]
            debug!("AsyncBorrowedFdStream: fd={fd} shutdown_in_bg");
            let mut result = shutdown_lock.lock().unwrap();
            if result.is_none() { // if shutdown not performed
                *result = Some(Self::raw_shutdown(fd));
                let mut wakers = wakers.lock().unwrap();
                wakers.drain(..).for_each(|w| w.wake());
                // Drop wakers last, and let fg lock wakers first.
                // This guarantees that if fg locks wakers but fails to lock buffer (and assumes that bg is IO-blocked),
                // the bg work is actually IO-blocked (and therefore the wakers it passes to bg WILL be checked)
                // instead of being in the teardown process (passed wakers will be ignored).
                #[cfg(debug_assertions)]
                debug!("AsyncBorrowedFdStream: fd={fd} shutdown_in_bg done ({:?}), dropping locks", *result);
                std::mem::drop(result);
                std::mem::drop(wakers);
            }
        });
    }
}

/// This is refactored from https://docs.rs/tokio/latest/tokio/io/unix/struct.AsyncFd.html, and its license is MIT
impl AsyncRead for AsyncBorrowedFdStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>
    ) -> Poll<io::Result<()>> {
        let fd = self.fd;
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: fd={fd} poll_read");
        // Lock wakers first. See comment in read_in_bg for rationale.
        let mut wakers = self.read_wakers.lock().unwrap();
        match self.read_lock.try_lock() {
            Ok(ref mut result0_buf1_off2) => {
                match result0_buf1_off2.0 {
                    // Read done and ready
                    Some(Ok(len)) => {
                        let off = result0_buf1_off2.2;
                        let remain = buf.remaining();
                        if remain < len {
                            // need to chunk it
                            assert!(off + len <= MAX_FRAME_SIZE, "Offset / read / MAX_FRAME_SIZE out of bound: {off} / {len} / {MAX_FRAME_SIZE}");
                            buf.put_slice(&result0_buf1_off2.1[off..off+remain]);
                            result0_buf1_off2.0 = Some(Ok(len - remain));
                            result0_buf1_off2.2 = off + remain;
                        } else {
                            // consume all
                            buf.put_slice(&result0_buf1_off2.1[off..off+len]);
                            result0_buf1_off2.0 = None;
                            result0_buf1_off2.2 = 0usize;
                            // ready for next reading
                            self.read_in_bg();
                        }
                        #[cfg(debug_assertions)]
                        debug!("AsyncBorrowedFdStream: fd={fd} poll_read => ready(ok({len}->{remain}))");
                        Poll::Ready(Ok(()))
                    },
                    // Read done and dead
                    Some(Err(ref e)) => {
                        info!("AsyncBorrowedFdStream: fd={fd} poll_read => ready(err)");
                        Poll::Ready(Err(std::io::Error::new(e.kind(), format!("{e}"))))
                    },
                    // Read not started yet
                    None => {
                        // Request wake
                        wakers.push(cx.waker().clone());
                        #[cfg(debug_assertions)]
                        debug!("AsyncBorrowedFdStream: fd={fd} poll_read => pending(early)");
                        Poll::Pending
                    },
                }
            },
            // Poisoned
            Err(std::sync::TryLockError::Poisoned(e)) => {
                // Just panic
                panic!("Background reading panicked: {e}");
            },
            // Read not ready
            Err(std::sync::TryLockError::WouldBlock) => {
                // Request wake
                wakers.push(cx.waker().clone());
                #[cfg(debug_assertions)]
                debug!("AsyncBorrowedFdStream: fd={fd} poll_read => pending(WouldBlock)");
                Poll::Pending
            },
        }
    }
}

impl AsyncWrite for AsyncBorrowedFdStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8]
    ) -> Poll<io::Result<usize>> {
        let fd = self.fd;
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: fd={fd} poll_write");
        // Lock wakers first. See comment in write_in_bg for rationale.
        let mut wakers = self.write_wakers.lock().unwrap();
        match self.write_lock.try_lock() {
            Ok(ref mut result0_buf1_len2_sd3) => {
                *self.shut_down.lock().unwrap() |= result0_buf1_len2_sd3.3; // if shut down, update
                match result0_buf1_len2_sd3.0 {
                    // Write done and ready for next
                    Some(Ok(_)) => {
                        if *self.shut_down.lock().unwrap() {
                            return Poll::Ready(Ok(0usize)); // if shut down, then keep as is and do nothing further
                        }
                        // max write is buf.len()
                        let input_len = result0_buf1_len2_sd3.1.len();
                        let len = std::cmp::min(input_len, buf.len());
                        result0_buf1_len2_sd3.0 = None;
                        result0_buf1_len2_sd3.1[..len].copy_from_slice(&buf[..len]);
                        result0_buf1_len2_sd3.2 = len;
                        assert!(!result0_buf1_len2_sd3.3, "write already shut down on non-shut-down branch");
                        self.write_in_bg();
                        // behave like buffered writer; claims Ready
                        #[cfg(debug_assertions)]
                        debug!("AsyncBorrowedFdStream: fd={fd} poll_write => ready(ok({input_len}->{len}))");
                        Poll::Ready(Ok(len))
                    },
                    // Write done and dead
                    Some(Err(ref e)) => {
                        info!("AsyncBorrowedFdStream: fd={fd} poll_write => ready(err)");
                        Poll::Ready(Err(std::io::Error::new(e.kind(), format!("{e}"))))
                    },
                    // Write not started yet
                    None => {
                        // Request wake
                        wakers.push(cx.waker().clone());
                        #[cfg(debug_assertions)]
                        debug!("AsyncBorrowedFdStream: fd={fd} poll_write => pending(early)");
                        Poll::Pending
                    },
                }
            },
            // Poisoned
            Err(std::sync::TryLockError::Poisoned(e)) => {
                // Just panic
                panic!("Background writing panicked: {e}");
            },
            // Write not ready
            Err(std::sync::TryLockError::WouldBlock) => {
                // Request wake
                wakers.push(cx.waker().clone());
                #[cfg(debug_assertions)]
                debug!("AsyncBorrowedFdStream: fd={fd} poll_write => pending(WouldBlock)");
                Poll::Pending
            },
        }
    }

    fn poll_flush(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<io::Result<()>> {
        let fd = self.fd;
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: fd={fd} poll_flush");
        // Lock wakers first. See comment in write_in_bg for rationale.
        let mut wakers = self.write_wakers.lock().unwrap();
        match self.write_lock.try_lock() {
            Ok(ref mut result0_buf1_len2_sd3) => {
                match result0_buf1_len2_sd3.0 {
                    // Write done and ready for next
                    Some(Ok(_)) => {
                        #[cfg(debug_assertions)]
                        debug!("AsyncBorrowedFdStream: fd={fd} poll_flush => ready(ok)");
                        Poll::Ready(Ok(()))
                    },
                    // Write done and dead
                    Some(Err(ref e)) => {
                        info!("AsyncBorrowedFdStream: fd={fd} poll_flush => ready(err)");
                        Poll::Ready(Err(std::io::Error::new(e.kind(), format!("{e}"))))
                    },
                    // Write not started yet
                    None => {
                        // Request wake
                        wakers.push(cx.waker().clone());
                        #[cfg(debug_assertions)]
                        debug!("AsyncBorrowedFdStream: fd={fd} poll_flush => pending(early)");
                        Poll::Pending
                    },
                }
            },
            // Poisoned
            Err(std::sync::TryLockError::Poisoned(e)) => {
                // Just panic
                panic!("Background flushing panicked: {e}");
            },
            // Write not ready
            Err(std::sync::TryLockError::WouldBlock) => {
                // Request wake
                wakers.push(cx.waker().clone());
                #[cfg(debug_assertions)]
                debug!("AsyncBorrowedFdStream: fd={fd} poll_flush => pending(WouldBlock)");
                Poll::Pending
            },
        }
    }

    fn poll_shutdown(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<io::Result<()>> {
        if self.skips_shut_down { // if you know it's gonna be denied, don't even try.
            #[cfg(debug_assertions)]
            debug!("AsyncBorrowedFdStream: fd={fd} poll_shutdown => ready(skipped, fake err)");
            return Poll::Ready(Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "Mock error: Permission denied (os error 13)")));
        }
        let fd = self.fd;
        #[cfg(debug_assertions)]
        debug!("AsyncBorrowedFdStream: fd={fd} poll_shutdown");
        if *self.shut_down.lock().unwrap() {
            #[cfg(debug_assertions)]
            debug!("AsyncBorrowedFdStream: fd={fd} poll_shutdown => ready(skipped)");
            Poll::Ready(Ok(()))
        } else {
            let mut wakers = self.shutdown_wakers.lock().unwrap();
            match self.shutdown_lock.try_lock() {
                Ok(result) => {
                    match *result {
                        // Shutdown done and successful
                        Some(Ok(_)) => {
                            *self.shut_down.lock().unwrap() = true;
                            #[cfg(debug_assertions)]
                            debug!("AsyncBorrowedFdStream: fd={fd} poll_shutdown => ready(ok)");
                            Poll::Ready(Ok(()))
                        },
                        // Shutdown done and dead
                        Some(Err(ref e)) => {
                            info!("AsyncBorrowedFdStream: fd={fd} poll_shutdown => ready(err)");
                            Poll::Ready(Err(std::io::Error::new(e.kind(), format!("{e}"))))
                        },
                        // Shutdown not scheduled or started yet
                        None => {
                            if wakers.is_empty() {
                                self.shutdown_in_bg();
                            }
                            // Request wake
                            wakers.push(cx.waker().clone());
                            #[cfg(debug_assertions)]
                            debug!("AsyncBorrowedFdStream: fd={fd} poll_shutdown => pending(start)");
                            Poll::Pending
                        },
                    }
                },
                // Poisoned
                Err(std::sync::TryLockError::Poisoned(e)) => {
                    // Just panic
                    panic!("Background shutting down panicked: {e}");
                },
                // Write not ready
                Err(std::sync::TryLockError::WouldBlock) => {
                    // Request wake
                    wakers.push(cx.waker().clone());
                    #[cfg(debug_assertions)]
                    debug!("AsyncBorrowedFdStream: fd={fd} poll_shutdown => pending(WouldBlock)");
                    Poll::Pending
                },
            }
        }
    }
}

impl AsRawFd for AsyncBorrowedFdStream {
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
/// Note: due to how blocking IO works, this class requires that any tokio::net::TcpStream be converted to std::net::TcpStream,
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

    fn forward_once(src: RawFd, tgt: RawFd, _dir: FwdDirection) -> io::Result<bool> {
        let mut buf = [0u8; MAX_FRAME_SIZE];
        let size = AsyncBorrowedFdStream::raw_read(src, &mut buf)?;
        if size == 0 {
            return Ok(true);
        }
        let (result, shut_down) = AsyncBorrowedFdStream::raw_write_all(tgt, &buf[..size]);
        result?;
        Ok(shut_down)
    }

    fn run_oneway_try(
        port: u16, src: RawFd, tgt: RawFd, dir: FwdDirection
    ) -> io::Result<()> {
        loop {
            match Self::forward_once(src, tgt, dir) {
                Ok(shutdown) => if shutdown { return Ok(()); },
                Err(e) => { error!("Failed in {dir:?} run_oneway_try for port {} fd {src}->{tgt}: {e}", port); return Err(e); }
            }
        }
    }

    fn run_oneway(
        port: u16, src: RawFd, tgt: RawFd, dir: FwdDirection
    ) -> io::Result<()> {
        let result = Self::run_oneway_try(port, src, tgt, dir);
        AsyncBorrowedFdStream::raw_shutdown(tgt)
            .inspect_err(|e| error!("Forwarding port {} fd {src}->{tgt} {dir:?} teardown: {e}", port)).ok();
        result
    }

    /// Run forwarding with tokio::task::spawn_blocking
    pub async fn run(self) -> io::Result<()> {
        let (local, remote, port) = (self.local, self.remote, self.port);
        info!("Forwarding session on port {port} fd {local}->{remote} started");
        let fw = task::spawn_blocking(move || AsyncForwarderSession::run_oneway(port, local, remote, FwdDirection::Fwd));
        let bw = task::spawn_blocking(move || AsyncForwarderSession::run_oneway(port, remote, local, FwdDirection::Bwd));
        let (fw, bw) = (fw.await, bw.await);
        match fw {
            Err(ref e) => { error!("Forwarding session (fwd) on port {} fd {local}->{remote} aborted: {e}", port); },
            Ok(Err(ref e)) => { error!("Forwarding session (fwd) on port {} fd {local}->{remote} failed: {e}", port); },
            _ => {},
        }
        match bw {
            Err(ref e) => { error!("Forwarding session (bwd) on port {} fd {local}->{remote} aborted: {e}", port); },
            Ok(Err(ref e)) => { error!("Forwarding session (bwd) on port {} fd {local}->{remote} failed: {e}", port); },
            _ => {},
        }
        fw??;
        bw??;
        info!("Forwarding session on port {port} fd {local}->{remote} ended successfully");
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
