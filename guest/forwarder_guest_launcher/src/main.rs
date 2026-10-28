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

//! Launcher of forwarder_guest

use anyhow::{anyhow, Context, Result};
use clap::Parser;
use debian_service::debian_service_client::DebianServiceClient;
use debian_service::{ActivePort, QueueOpeningRequest, ReportVmActivePortsRequest};
use log::{debug, error};
use std::collections::HashMap;
use std::net::SocketAddr;
use tokio::try_join;
use tonic::transport::{Channel, Endpoint};
use tonic::Request;

mod debian_service {
    tonic::include_proto!("com.android.virtualization.terminal.proto");
}

#[derive(Parser)]
/// Flags for running command
pub struct Args {
    /// grpc port number
    #[arg(long)]
    grpc_port: u16,
}

async fn process_forwarding_request_queue(mut client: DebianServiceClient<Channel>) -> Result<()> {
    let cid = vsock::get_local_cid().context("Failed to get CID of VM")?;
    let mut res_stream = client
        .open_forwarding_request_queue(Request::new(QueueOpeningRequest { cid: cid as i32 }))
        .await?
        .into_inner();

    while let Some(response) = res_stream.message().await? {
        let tcp_port = i16::try_from(response.guest_tcp_port)
            .context("Failed to convert guest_tcp_port as i16")?;
        let vsock_port = response.vsock_port as u32;

        debug!(
            "executing forwarder_guest with guest_tcp_port: {:?}, vsock_port: {:?}",
            &tcp_port, &vsock_port
        );

        forwarder_guest_launcher::forward_port(tcp_port.try_into().unwrap(), vsock_port);
    }
    Err(anyhow!("process_forwarding_request_queue is terminated"))
}

async fn send_active_ports_report(
    listening_ports: &HashMap<SocketAddr, String>,
    client: &mut DebianServiceClient<Channel>,
) -> Result<()> {
    let ports = listening_ports
        .iter()
        .map(|(port, comm)| ActivePort { port: *port as i32, comm: comm.clone() })
        .collect();

    let res = client
        .report_vm_active_ports(Request::new(ReportVmActivePortsRequest { ports }))
        .await?
        .into_inner();
    if res.success {
        debug!("Successfully reported active ports to the host");
    } else {
        error!("Failure response received from the host for reporting active ports");
    }
    Ok(())
}

async fn report_active_ports(mut client: DebianServiceClient<Channel>) -> Result<()> {
    forwarder_guest_launcher::monitor_active_ports(async |ports| {
        send_active_ports_report(ports, &mut client).await
    })
    .await?;
    Err(anyhow!("report_active_ports is terminated"))
}

#[tokio::main]
async fn main() -> Result<()> {
    env_logger::builder().filter_level(log::LevelFilter::Debug).init();
    debug!("Starting forwarder_guest_launcher");
    let args = Args::parse();
    let gateway_ip_addr = linux::net::get_default_gateway()?;

    let grpc_port = args.grpc_port.to_string();

    let addr = format!("https://{}:{}", gateway_ip_addr.to_string(), grpc_port);
    let channel = Endpoint::from_shared(addr)?.connect().await?;
    let client = DebianServiceClient::new(channel);

    debug!("Starting to monitor and forwarding ports");
    try_join!(process_forwarding_request_queue(client.clone()), report_active_ports(client))?;
    Ok(())
}
