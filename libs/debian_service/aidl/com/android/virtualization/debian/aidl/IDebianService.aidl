/*
 * Copyright 2025 The Android Open Source Project
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
package com.android.virtualization.debian.aidl;

import com.android.virtualization.debian.aidl.IVmActivePortListener;

// Controls debian guest agents from Terminal App
interface IDebianService {
    const long VSOCK_PORT = 4000;

    oneway void setVmActivePortListener(IVmActivePortListener listener);
    void requestForwarding(int guestTcpPort, int vsockPort);
    oneway void requestStorageBalloon(long availableBytes);

    /**
     * Updates the clipboard in the guest.
     */
    oneway void updateClipboard(String text);

    /**
     * Reads the current clipboard content from the guest.
     */
    String readClipboard();
}
