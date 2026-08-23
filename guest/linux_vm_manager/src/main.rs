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

//! Linux VM Manager

mod debian_service;
mod guest_agent;

use rsbinder::*;
mod aidl {
    rsbinder::include_aidl!("aidl");
}
use guest_agent::GuestAgent;
// use debian_aidl_interface::binder::Strong;
use aidl::android::system::virtualmachineservice::IVirtualMachineService::IVirtualMachineService;
use crate::debian_service::DebianService;
use anyhow::{Context, Result};
use rsbinder::rpc::{RpcSession, wire_android13::PROTOCOL_V2};
use vsock::VMADDR_CID_HOST;
use std::{panic, thread, time};
use std::process::exit;
use std::sync::Arc;
use log::{error, warn, info};

fn wrap_error<T>(message: &str) -> impl Fn(T) -> Status
where T: std::error::Error
{
    let message_str = message.to_string();
    let wrapper = move |e| Status::new_service_specific_error(-1, Some(format!("{message_str}: {e:?}")));
    wrapper
}

pub struct DeathReporter {
    tag: String,
}

impl DeathReporter {
    pub fn new(tag: &str) -> DeathReporter {
        DeathReporter{ tag: tag.into() }
    }
}

impl DeathRecipient for DeathReporter {
    fn binder_died(&self, who: &WIBinder) {
        error!("'{0}': Binder {who:#?} died", self.tag)
    }
}

fn get_vms_rpc_binder(death_recipient: Arc<DeathReporter>) -> Result<(RpcSession, Strong<dyn IVirtualMachineService>)> {
    let vsock_port = vsock::get_local_cid().context("Could not determine local CID")?;
    info!("Starting service with cid={vsock_port}");

    let socket = "/tmp/IVMS.socket";
    info!("Launching socat {socket} <> VSOCK-CONNECT:{VMADDR_CID_HOST}:{vsock_port}");
    std::fs::remove_file(socket).ok();
    std::process::Command::new("socat")
        .arg("-d0")
        .arg(format!("UNIX-LISTEN:{socket},reuseaddr,fork"))
        .arg(format!("VSOCK-CONNECT:{VMADDR_CID_HOST}:{vsock_port}"))
        .spawn()
        .map_err(wrap_error("Could not spawn socat"))?;
    info!("Launched socat");
    info!("Sleeping 0.5s...");
    thread::sleep(time::Duration::from_millis(500));
    info!("Negotiating protocol and max threads");
    let session = RpcSession::setup_unix_client_android13plus(socket, PROTOCOL_V2)
        .map_err(wrap_error("Could not connect to IVirtualMachineService"))?;
    session.negotiate(1).map_err(|e| warn!("Could not negotiate max threads for IVirtualMachineService: {e:?}")).ok();
    info!("Completed negotiating protocol and max threads");
    info!("Obtaining binder for IVirtualMachineService");
    let binder = session
        .get_root().map_err(wrap_error("Could not get binder for IVirtualMachineService"))?;
    info!("Obtained binder for IVirtualMachineService");
    binder.link_to_death_arc(&death_recipient).map_err(|e| warn!("Could not link to death recipient: {e:?}")).ok();
    info!("Obtaining IVirtualMachineService");
    let iface: Strong<dyn IVirtualMachineService> = <dyn IVirtualMachineService as FromIBinder>::try_from(binder)
        .map_err(wrap_error("Could not cast binder to interface IVirtualMachineService"))?;
    info!("Obtained IVirtualMachineService");
    Ok((session, iface))
}

fn main() -> Result<()> {
    env_logger::builder().filter_level(log::LevelFilter::Debug).init();
    info!("Starting linux_vm_manager");

    // Redirect panic messages to stderr with backtrace
    panic::set_hook(Box::new(|panic_info| {
        error!("Panic: {panic_info}");
        let backtrace = std::backtrace::Backtrace::force_capture();
        error!("Backtrace: {:#?}", backtrace);
        exit(1);
    }));

    info!("DebianService::new_rpc_server()");
    let debian_server = DebianService::new_rpc_server();
    info!("DebianService::new_rpc_server() completed");

    let death_recipient = Arc::new(DeathReporter::new("IVirtualMachineService"));
    let session_service = get_vms_rpc_binder(death_recipient.clone());
    match session_service {
        Ok((_session, service)) => {
            info!("Creating guest_agent");
            let guest_agent = GuestAgent::new_binder();
            info!("Created guest_agent");
            info!("Registering guest agent");
            if let Err(e) = service.registerGuestAgent(&guest_agent) {
                error!("Failed to register GuestAgent: {e:?}");
            } else {
                info!("linux_vm_manager with debian service started, and guest agent registered");
            }
        },
        Err(e) => {
            error!("Failed to connect to VirtualizationService: {e:?}");
        }
    }

    info!("Sleeping for one millennium"); // lol
    thread::sleep(time::Duration::from_hours(24*365242));
    debian_server.join_workers();

    info!("linux_vm_manager is shutting down");

    Ok(())
}
