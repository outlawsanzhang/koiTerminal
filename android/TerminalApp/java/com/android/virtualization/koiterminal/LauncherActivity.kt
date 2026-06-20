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
package com.android.virtualization.koiterminal

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.android.system.virtualmachine.flags.Flags
import com.android.virtualization.koiterminal.new2.ui.MainActivity as NewUiMainActivity
import java.nio.file.Files

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent =
            if (terminalNewuiJetpack(this)) {
                Intent(this, NewUiMainActivity::class.java)
            } else {
                Intent(this, MainActivity::class.java)
            }
        startActivity(intent)
        finish()
    }

    companion object {
        fun terminalNewuiJetpack(context: Context): Boolean {
            val oldUiMarker = context.getFilesDir().toPath().resolve("use_old_ui")
            val terminalNewuiJetpack = !Files.exists(oldUiMarker)
            Log.i("VmTerminalApp", "Selecting new UI? $terminalNewuiJetpack from !$oldUiMarker")
            return terminalNewuiJetpack
        }
    }
}
