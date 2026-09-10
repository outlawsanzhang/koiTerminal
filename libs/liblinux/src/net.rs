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

//! Libraries for Linux network

use crate::proc::ProcHelper;
use anyhow::{anyhow, Context, Error};
use std::collections::HashMap;
use std::fs::File;
use std::io::{self, BufRead};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

// Parse kernel IP to IpAddr (e.g. "0100007F" -> Ipv4Addr([127, 0, 0, 1]))
fn parse_ip_addr(ip_from_kernel: &str) -> Result<IpAddr, Error> {
    // Kernel keeps the IP address as big endian while Ipv{4,6}Addr takes native endian.
    let ip_be = u32::from_str_radix(ip_from_kernel, 16)
        .with_context(|| format!("Cannot parse {ip_from_kernel} to Ipv4Addr"))?;
    let ip_ne = u32::from_be(ip_be);
    Ok(IpAddr::V4(Ipv4Addr::from_bits(ip_ne)))
}

// Parse IP string from kernel (e.g. "0100007F:1F40" -> 127.0.0.1:8000")
fn parse_socket_addr(socket_from_kernel: &str) -> Result<SocketAddr, Error> {
    let addr: Vec<_> = socket_from_kernel.split(':').collect();
    let ip = parse_ip_addr(addr[0])?;
    let port = u16::from_str_radix(addr[1], 16)
        .with_context(|| format!("Cannot parse {socket_from_kernel} to SockAddr"))?;
    Ok(SocketAddr::new(ip, port))
}

/// Get default gateway in IPV4 as string by reading /proc/net/route
pub fn get_default_gateway() -> Result<String, Error> {
    const DEFAULT_ROUTE_DESTINATION: &str = "00000000";

    // /proc/net/route isn't an ordinary file, so better not use tokio.
    let file = File::open("/proc/net/route")
        .context("Failed to open /proc/net/route. Ensure this is running with Linux kernel")?;
    let mut lines = io::BufReader::new(file).lines();

    let header_line = lines.next().ok_or(anyhow!("Cannot find header from /proc/net/route"))??;
    let headers: Vec<_> = header_line.split_whitespace().collect();
    let dest_idx = headers
        .iter()
        .position(|col| *col == "Destination")
        .ok_or(anyhow!("Cannot find 'Destination' from header"))?;
    let gateway_idx = headers
        .iter()
        .position(|col| *col == "Gateway")
        .ok_or(anyhow!("Cannot find 'Gateway' from header"))?;

    while let Some(Ok(line)) = lines.next() {
        let items: Vec<_> = line.split_whitespace().collect();
        if items[dest_idx] == DEFAULT_ROUTE_DESTINATION {
            return Ok(parse_ip_addr(items[gateway_idx])?.to_string());
        }
    }
    Err(anyhow!("Cannot find default gateway"))
}

/// Get map of (port, comm) which lists listening IPv4 TCP port available from loopback
/// This reads /proc/net/tcp, so result may vary per permission.
pub fn get_listening_tcp4_ports_from_localhost() -> Result<HashMap<u16, String>, Error> {
    let proc = ProcHelper::new()?;

    let file = File::open("/proc/net/tcp")
        .context("Failed to open /proc/net/tcp. Ensure this is running with Linux kernel")?;
    let mut lines = io::BufReader::new(file).lines();

    // /proc/net/tcp{,6} format is stable and documented below.
    // https://www.kernel.org/doc/Documentation/networking/proc_net_tcp.txt
    const LOCAL_SOCK_ADDR_IDX: usize = 1;
    const CONNECTION_STATE_IDX: usize = 3;
    const INODE_IDX: usize = 9;

    // From include/netinet/tcp.h
    const TCP_LISTEN: &str = "0A";

    // Skip header validation.
    let _ = lines.next().ok_or(anyhow!("Cannot find header from /proc/net/tcp"))?;

    let mut entries: HashMap<u16, String> = Default::default();
    while let Some(Ok(line)) = lines.next() {
        let items: Vec<_> = line.split_whitespace().collect();
        if items[CONNECTION_STATE_IDX] != TCP_LISTEN {
            continue;
        }
        let socket_addr = parse_socket_addr(items[LOCAL_SOCK_ADDR_IDX])?;
        if !socket_addr.ip().is_loopback() && !socket_addr.ip().is_unspecified() {
            continue;
        }
        let comm = match proc.comm_with_inode(items[INODE_IDX].parse()?) {
            Some(comm) => comm,
            None => "unknown".to_string(),
        };
        entries.insert(socket_addr.port(), comm.clone());
    }
    Ok(entries)
}

/// This test runs on the host Linux.
#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::ensure;
    use std::process::Command;

    fn is_tool_available(tool_name: &str) -> bool {
        Command::new("which").arg(tool_name).output().is_ok_and(|which| which.status.success())
    }

    #[test]
    fn test_get_default_gateway() -> Result<(), Error> {
        let gateway = get_default_gateway()?;

        let ip_route = if is_tool_available("route") {
            Command::new("bash")
                .arg("-c")
                .arg("route | grep -e '^default' | awk '{print $2}'")
                .output()?
        } else if is_tool_available("ip") {
            Command::new("bash")
                .arg("-c")
                .arg("ip route | grep -e '^default' | awk '{print $3}'")
                .output()?
        } else {
            println!("Skipping test. Requires either route or ip");
            return Ok(());
        };
        let ip_route_gateway = str::from_utf8(&ip_route.stdout)
            .with_context(|| format!("Failed to read default route. {ip_route:?}"))?
            .trim();
        ensure!(!ip_route_gateway.is_empty(), "Failed to find default route. {ip_route:?}");

        assert_eq!(gateway, ip_route_gateway);
        Ok(())
    }

    #[test]
    fn test_get_listening_tcp_ports() -> Result<(), Error> {
        let mut tcp = get_listening_tcp4_ports_from_localhost()?;

        let port_comm = if is_tool_available("netstat") {
            Command::new("bash")
                .arg("-c")
                .arg(r#"ss -4lntp | awk '{print $4,$6}' | sed -n 's/^\(127.0.0.[0-7]\|0.0.0.0\):\(.*\)\s*users:(("\(.*\)".*$/\2 \3/p'"#)
                .output()
                .context("Failed to run ss")?
        } else if is_tool_available("ss") {
            // Returned comm is truncated to 11 chars, while original comm is capped to 15 chars.
            Command::new("bash")
                .arg("-c")
                .arg(r#"netstat -4lntp | awk '{print $4,$7}' | sed -n 's/^\(127.0.0.[0-7]\|0.0.0.0\):\([0-9]*\)\s*[0-9]*\/\(.*\)$/\2 \3/p'"#)
                .output()
                .context("Failed to run netstat")?
        } else {
            println!("Skipping test. Requires either ss or netstat");
            return Ok(());
        };
        let stdout = str::from_utf8(&port_comm.stdout).context("Failed to read listening ports")?;
        for line in stdout.lines() {
            if line.is_empty() {
                continue;
            }
            let items: Vec<_> = line.split_whitespace().collect();
            let (port, comm) = (items[0].parse::<u16>()?, items[1]);

            let existing =
                tcp.remove(&port).ok_or(anyhow!("Failed to find port {port} from {tcp:?}"))?;
            assert!(
                existing.starts_with(comm),
                "Unexpected comm name. Expected {comm} with {port}, but was {existing}."
            )
        }

        assert!(tcp.is_empty(), "Unexpected left-over ports. {tcp:?}");
        Ok(())
    }
}
