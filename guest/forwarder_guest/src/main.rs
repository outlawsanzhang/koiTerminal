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
// src/platform2/vm_tools/chunnel/src/bin/chunnel.rs

//! Guest-side stream socket forwarder

use std::fmt;
use std::result;

use clap::Parser;
use forwarder::forwarder::{ForwarderError, ForwarderSession};
use forwarder::stream::{StreamSocket, StreamSocketError};
use log::{error, info};
use poll_token_derive::PollToken;
use vmm_sys_util::poll::{PollContext, PollToken};

#[remain::sorted]
#[derive(Debug)]
enum Error {
    ConnectSocket(StreamSocketError),
    Forward(ForwarderError),
    PollContextAdd(vmm_sys_util::errno::Error),
    PollContextDelete(vmm_sys_util::errno::Error),
    PollContextNew(vmm_sys_util::errno::Error),
    PollWait(vmm_sys_util::errno::Error),
}

type Result<T> = result::Result<T, Error>;

impl fmt::Display for Error {
    #[remain::check]
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        use self::Error::*;

        #[remain::sorted]
        match self {
            ConnectSocket(e) => write!(f, "failed to connect socket: {}", e),
            Forward(e) => write!(f, "failed to forward traffic: {}", e),
            PollContextAdd(e) => write!(f, "failed to add fd to poll context: {}", e),
            PollContextDelete(e) => write!(f, "failed to delete fd from poll context: {}", e),
            PollContextNew(e) => write!(f, "failed to create poll context: {}", e),
            PollWait(e) => write!(f, "failed to wait for poll: {}", e),
        }
    }
}

fn run_forwarder(local_stream: StreamSocket, remote_stream: StreamSocket) -> Result<()> {
    #[derive(PollToken)]
    enum Token {
        LocalStreamReadable,
        RemoteStreamReadable,
    }
    let poll_ctx: PollContext<Token> = PollContext::new().map_err(Error::PollContextNew)?;
    poll_ctx.add(&local_stream, Token::LocalStreamReadable).map_err(Error::PollContextAdd)?;
    poll_ctx.add(&remote_stream, Token::RemoteStreamReadable).map_err(Error::PollContextAdd)?;

    let mut forwarder = ForwarderSession::new(local_stream, remote_stream);

    loop {
        let events = poll_ctx.wait().map_err(Error::PollWait)?;

        for event in events.iter_readable() {
            match event.token() {
                Token::LocalStreamReadable => {
                    let shutdown = forwarder.forward_from_local(false).map_err(Error::Forward)?;
                    if shutdown {
                        poll_ctx
                            .delete(forwarder.local_stream())
                            .map_err(Error::PollContextDelete)?;
                    }
                }
                Token::RemoteStreamReadable => {
                    let shutdown = forwarder.forward_from_remote(false).map_err(Error::Forward)?;
                    if shutdown {
                        poll_ctx
                            .delete(forwarder.remote_stream())
                            .map_err(Error::PollContextDelete)?;
                    }
                }
            }
        }
        if forwarder.is_shut_down() {
            return Ok(());
        }
    }
}

#[derive(Parser)]
/// Flags for running command
pub struct Args {
    /// Local socket address
    #[arg(long)]
    #[arg(alias = "local")]
    local_sockaddr: String,

    /// Remote socket address
    #[arg(long)]
    #[arg(alias = "remote")]
    remote_sockaddr: String,
}

fn try_main() -> Result<()> {
    let args = Args::parse();

    info!(
        "forwarder_guest is started with local={}, remote={}",
        args.local_sockaddr, args.remote_sockaddr
    );

    let local_stream = StreamSocket::connect(&args.local_sockaddr).map_err(Error::ConnectSocket)?;
    let remote_stream =
        StreamSocket::connect(&args.remote_sockaddr).map_err(Error::ConnectSocket)?;

    run_forwarder(local_stream, remote_stream)
}

// TODO(b/370897694): Support forwarding for datagram socket
fn main() -> Result<()> {
    env_logger::builder().filter_level(log::LevelFilter::Debug).init();

    if let Err(e) = try_main() {
        error!("Error in forwarder_guest, {e:?}");
    }

    Ok(())
}
