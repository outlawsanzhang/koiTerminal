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
// src/platform2/vm_tools/chunnel/src/forwarder.rs

//! This module contains forwarding mechanism between stream sockets.

use std::fmt;
use std::io::{self, Read, Write};
use std::result;

use crate::stream::StreamSocket;

/// This was picked arbitrarily. crosvm doesn't yet use VIRTIO_NET_F_MTU, so there's no reason to
/// opt for massive 65535 byte frames.
pub const MAX_FRAME_SIZE: usize = 8192;

/// Errors that can be encountered by a ForwarderSession.
#[remain::sorted]
#[derive(Debug)]
pub enum ForwarderError {
    /// An io::Error was encountered while reading from a stream.
    ReadFromStream(io::Error),
    /// An io::Error was encountered while shutting down writes on a stream.
    ShutDownStream(io::Error),
    /// An io::Error was encountered while writing to a stream.
    WriteToStream(io::Error),
}

type Result<T> = result::Result<T, ForwarderError>;

impl fmt::Display for ForwarderError {
    #[remain::check]
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        use self::ForwarderError::*;

        #[remain::sorted]
        match self {
            ReadFromStream(e) => write!(f, "failed to read from stream: {e}"),
            ShutDownStream(e) => write!(f, "failed to shut down stream: {e}"),
            WriteToStream(e) => write!(f, "failed to write to stream: {e}"),
        }
    }
}

/// A ForwarderSession owns stream sockets that it forwards traffic between.
pub struct ForwarderSession {
    local: StreamSocket,
    remote: StreamSocket,
}

fn forward(from_stream: &mut StreamSocket, to_stream: &mut StreamSocket) -> Result<bool> {
    let mut buf = [0u8; MAX_FRAME_SIZE];

    let count = from_stream.read(&mut buf).map_err(ForwarderError::ReadFromStream)?;
    if count == 0 {
        to_stream.shut_down_write().map_err(ForwarderError::ShutDownStream)?;
        return Ok(true);
    }

    to_stream.write_all(&buf[..count]).map_err(ForwarderError::WriteToStream)?;
    Ok(false)
}

impl ForwarderSession {
    /// Creates a forwarder session from a local and remote stream socket.
    pub fn new(local: StreamSocket, remote: StreamSocket) -> Self {
        ForwarderSession { local, remote }
    }

    /// Forwards traffic from the local socket to the remote socket.
    /// Returns true if the local socket has reached EOF and the
    /// remote socket has been shut down for further writes.
    pub fn forward_from_local(&mut self) -> Result<bool> {
        forward(&mut self.local, &mut self.remote)
    }

    /// Forwards traffic from the remote socket to the local socket.
    /// Returns true if the remote socket has reached EOF and the
    /// local socket has been shut down for further writes.
    pub fn forward_from_remote(&mut self) -> Result<bool> {
        forward(&mut self.remote, &mut self.local)
    }

    /// Returns a reference to the local stream socket.
    pub fn local_stream(&self) -> &StreamSocket {
        &self.local
    }

    /// Returns a reference to the remote stream socket.
    pub fn remote_stream(&self) -> &StreamSocket {
        &self.remote
    }

    /// Returns true if both sockets are completely shut down and the session can be dropped.
    pub fn is_shut_down(&self) -> bool {
        self.local.is_shut_down() && self.remote.is_shut_down()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::Shutdown;
    use std::os::unix::net::UnixStream;

    #[test]
    fn forward_unix() {
        // Local streams.
        let (mut london, folkestone) = UnixStream::pair().unwrap();
        // Remote streams.
        let (coquelles, mut paris) = UnixStream::pair().unwrap();

        // Connect the local and remote sockets via the chunnel.
        let mut forwarder = ForwarderSession::new(folkestone.into(), coquelles.into());

        // Put some traffic in from London.
        let greeting = b"hello";
        london.write_all(greeting).unwrap();

        // Expect forwarding from the local end not to have reached EOF.
        assert!(!forwarder.forward_from_local().unwrap());
        let mut salutation = [0u8; 8];
        let count = paris.read(&mut salutation).unwrap();
        assert_eq!(greeting.len(), count);
        assert_eq!(greeting, &salutation[..count]);

        // Shut the local socket down. The forwarder should detect this and perform a shutdown,
        // which will manifest as an EOF when reading.
        london.shutdown(Shutdown::Write).unwrap();
        assert!(forwarder.forward_from_local().unwrap());
        assert_eq!(paris.read(&mut salutation).unwrap(), 0);

        // Don't consider the forwarder shut down until both ends are.
        assert!(!forwarder.is_shut_down());

        // Forward traffic from the remote end.
        let salutation = b"bonjour";
        paris.write_all(salutation).unwrap();

        // Expect forwarding from the remote end not to have reached EOF.
        assert!(!forwarder.forward_from_remote().unwrap());
        let mut greeting = [0u8; 8];
        let count = london.read(&mut greeting).unwrap();
        assert_eq!(salutation.len(), count);
        assert_eq!(salutation, &greeting[..count]);

        // Shut the remote socket down. The forwarder should detect this and perform a shutdown,
        // which will manifest as an EOF when reading.
        paris.shutdown(Shutdown::Write).unwrap();
        assert!(forwarder.forward_from_remote().unwrap());
        assert_eq!(london.read(&mut greeting).unwrap(), 0);

        // The forwarder should now be considered shut down.
        assert!(forwarder.is_shut_down());
    }
}
