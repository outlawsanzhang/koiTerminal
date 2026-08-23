// Copyright 2025, The Android Open Source Project
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

//! A library that handles port forwarding

use anyhow::Result;
use log::{debug, error};
use std::collections::HashMap;

const NON_PREVILEGED_PORT_RANGE_START: u16 = 1024;
const TTYD_PORT: u16 = 7681;

/// Forward tcp port over vsock for host to connect to it.
pub fn forward_port(tcp_port: u16, vsock_port: u32) {
    // Use std::process::Command which doesn't require Tokio context.
    if let Err(e) = std::process::Command::new("forwarder_guest")
        .arg("--local")
        .arg(format!("127.0.0.1:{}", tcp_port))
        .arg("--remote")
        .arg(format!("vsock:2:{}", vsock_port))
        .spawn()
    {
        error!("Failed to launch forwarder_guest, err={e:?}");
    }
}

fn is_forwardable_port(port: u16) -> bool {
    port >= NON_PREVILEGED_PORT_RANGE_START && port != TTYD_PORT
}

/// Monitor active ports, and notify map of port and comm when there's any change.
pub async fn monitor_active_ports<T>(mut listener: T) -> Result<()>
where
    T: AsyncFnMut(&HashMap<u16, String>) -> Result<()>,
{
    debug!("Collecting already opened ports");
    let mut listening_ports: HashMap<_, _> = linux::net::get_listening_tcp4_ports_from_localhost()?
        .into_iter()
        .filter(|(x, _)| is_forwardable_port(*x))
        .inspect(|(port, comm)| debug!("Port {port} is already opened by {comm:?}"))
        .collect();

    listener(&listening_ports).await?;

    debug!("Starting monitoring ports");
    loop {
        let listening_ports_new: HashMap<_, _> = linux::net::get_listening_tcp4_ports_from_localhost()?
            .into_iter()
            .filter(|(x, _)| is_forwardable_port(*x))
            .collect();
        if listening_ports != listening_ports_new {
            listening_ports = listening_ports_new;
            listener(&listening_ports).await?;
        } else {
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        }
    }
}
