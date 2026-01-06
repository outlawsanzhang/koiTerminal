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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.startup.Initializer
import androidx.window.embedding.ActivityFilter
import androidx.window.embedding.EmbeddingAspectRatio
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitPairFilter
import androidx.window.embedding.SplitPairRule
import androidx.window.embedding.SplitPlaceholderRule
import androidx.window.embedding.SplitRule
import com.android.system.virtualmachine.flags.Flags

class SplitInitializer : Initializer<RuleController> {

    override fun create(context: Context): RuleController {
        val filters =
            mutableSetOf(
                SplitPairFilter(
                    ComponentName(context, SettingsActivity::class.java),
                    ComponentName(context, SettingsPortForwardingActivity::class.java),
                    null,
                )
            )

        if (Flags.terminalStorageBalloon()) {
            filters.add(
                SplitPairFilter(
                    ComponentName(context, SettingsActivity::class.java),
                    ComponentName(context, SettingsDiskResizeActivity::class.java),
                    null,
                )
            )
        }

        filters.add(
            SplitPairFilter(
                ComponentName(context, SettingsActivity::class.java),
                ComponentName(context, SettingsRecoveryActivity::class.java),
                null,
            )
        )
        val splitPairRules =
            SplitPairRule.Builder(filters)
                .setClearTop(true)
                .setFinishPrimaryWithSecondary(SplitRule.FinishBehavior.ADJACENT)
                .setFinishSecondaryWithPrimary(SplitRule.FinishBehavior.ALWAYS)
                .setDefaultSplitAttributes(
                    SplitAttributes.Builder()
                        .setLayoutDirection(SplitAttributes.LayoutDirection.LOCALE)
                        .setSplitType(
                            SplitAttributes.SplitType.ratio(
                                context.resources.getFloat(R.dimen.activity_split_ratio)
                            )
                        )
                        .build()
                )
                .setMaxAspectRatioInPortrait(EmbeddingAspectRatio.ALWAYS_ALLOW)
                .setMinWidthDp(context.resources.getInteger(R.integer.split_min_width))
                .build()

        val placeholderRule =
            SplitPlaceholderRule.Builder(
                    setOf(
                        ActivityFilter(ComponentName(context, SettingsActivity::class.java), null)
                    ),
                    Intent(context, SettingsDiskResizeActivity::class.java),
                )
                .setFinishPrimaryWithPlaceholder(SplitRule.FinishBehavior.ADJACENT)
                .setDefaultSplitAttributes(
                    SplitAttributes.Builder()
                        .setLayoutDirection(SplitAttributes.LayoutDirection.LOCALE)
                        .setSplitType(
                            SplitAttributes.SplitType.ratio(
                                context.resources.getFloat(R.dimen.activity_split_ratio)
                            )
                        )
                        .build()
                )
                .setMaxAspectRatioInPortrait(EmbeddingAspectRatio.ALWAYS_ALLOW)
                .setMinWidthDp(context.resources.getInteger(R.integer.split_min_width))
                .setSticky(false)
                .build()

        val ruleController = RuleController.getInstance(context)
        ruleController.addRule(splitPairRules)
        ruleController.addRule(placeholderRule)

        return ruleController
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
