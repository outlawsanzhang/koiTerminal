/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.virtualization.terminal

import android.content.Context
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineException
import android.system.virtualmachine.VirtualMachineManager
import android.util.Log
import com.android.virtualization.terminal.MainActivity.Companion.TAG
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool

/** Utility class for creating a VM and waiting for it to finish. */
internal class Runner private constructor(val vm: VirtualMachine, callback: Callback) {
    /** Get future about VM's exit status. */
    val exitStatus = callback.finishedSuccessfully

    /** Future which is set to true when VM has started shutdown */
    val shutdownStarted: CompletableFuture<Void> = CompletableFuture<Void>()

    private class Callback : VirtualMachineCallback {
        val finishedSuccessfully: CompletableFuture<Boolean> = CompletableFuture<Boolean>()

        override fun onPayloadStarted(vm: VirtualMachine) {
            // This event is only from Microdroid-based VM. Custom VM shouldn't emit this.
        }

        override fun onPayloadReady(vm: VirtualMachine) {
            // This event is only from Microdroid-based VM. Custom VM shouldn't emit this.
        }

        override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {
            // This event is only from Microdroid-based VM. Custom VM shouldn't emit this.
        }

        override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
            Log.e(TAG, "Error from VM. code: $errorCode ($message)")
            finishedSuccessfully.complete(false)
        }

        override fun onStopped(vm: VirtualMachine, reason: Int) {
            Log.d(TAG, "VM stopped. Reason: $reason")
            finishedSuccessfully.complete(true)
        }
    }

    companion object {
        /** Create a virtual machine of the given config, under the given context. */
        @Throws(VirtualMachineException::class)
        fun create(context: Context, config: VirtualMachineConfig): Runner {
            // context may already be the app context, but calling this again is not harmful.
            // See b/359439878 on why vmm should be obtained from the app context.
            val appContext = context.getApplicationContext()
            val vmm =
                appContext.getSystemService<VirtualMachineManager>(
                    VirtualMachineManager::class.java
                )
            val customConfig = config.customImageConfig
            requireNotNull(customConfig) { "CustomImageConfig is missing" }

            val name = customConfig.name
            require(!name.isNullOrEmpty()) { "Virtual machine's name is missing in the config" }

            // If there's a VM with the same name, delete it. And before deleting it, ensure that
            // it's stopped. This can happen especially when the user started the terminal app right
            // after they dismissed it.
            var oldVm =
                try {
                    vmm.get(name)
                } catch (e: VirtualMachineException) {
                    Log.e(TAG, "Failed to get old VM. $e")
                    null
                }
            if (oldVm != null) {
                if (oldVm.getStatus() != VirtualMachine.STATUS_STOPPED) {
                    val cb = Callback()
                    Log.i(TAG, "Virtual machine ($name) is running. Waiting for it to stop.")
                    oldVm.setCallback(ForkJoinPool.commonPool(), cb)
                    cb.finishedSuccessfully.join()
                }
                vmm.delete(name)
            }

            var vm = vmm.create(name, config)
            val cb = Callback()
            vm.setCallback(ForkJoinPool.commonPool(), cb)
            vm.run()
            return Runner(vm, cb)
        }
    }
}
