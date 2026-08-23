/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

/**
 * Interface for providing RPC service connection information to a
 * centralized servicemanager instance.
 *
 * This is useful when remote services are known/managed by some other
 * process, possibly on another device. That process can implement this
 * interface and it can be used by a servicemanager instance on the local
 * device to allow clients to connect to the remote services over sockets.
 */
interface IRpcProvider {
    /**
     * Connection info for a service
     */
    @RustDerive(Clone=true, Eq=true, PartialEq=true)
    parcelable Vsock {
        int cid;
        int port;
    }
    @RustDerive(Clone=true, Eq=true, PartialEq=true)
    union ServiceConnectionInfo {
        Vsock vsock;
    }

    /**
     * Request VSOCK connection info for a specific host service.
     */
    ServiceConnectionInfo getServiceConnectionInfo(in String name);
}
