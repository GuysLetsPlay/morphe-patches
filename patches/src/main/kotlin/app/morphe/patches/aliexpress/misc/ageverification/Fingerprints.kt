/*
 * Copyright (C) 2026 Lorenz
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.aliexpress.misc.ageverification

import app.morphe.patcher.Fingerprint

private const val SETTING_SERVICE_IMPL = "Lcom/aliexpress/module/settings/service/SettingServiceImpl;"

internal object ShowSafetyFilterForEuFingerprint : Fingerprint(
    definingClass = SETTING_SERVICE_IMPL,
    name = "showSafetyFilterForEu",
    parameters = listOf("Lcom/aliexpress/module/settings/service/SafetyFilterForEuCallback;"),
    returnType = "V",
)

internal object ShowUniversalSafetyFilterFingerprint : Fingerprint(
    definingClass = SETTING_SERVICE_IMPL,
    name = "showUniversalSafetyFilter",
    parameters = listOf(
        "Lcom/alibaba/fastjson/JSONObject;",
        "Lcom/aliexpress/module/settings/service/UniversalSafetyFilterCallback;",
    ),
    returnType = "V",
)
