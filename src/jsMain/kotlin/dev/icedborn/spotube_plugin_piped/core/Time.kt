package dev.icedborn.spotube_plugin_piped.core

import kotlin.js.Date

internal actual fun epochMillis(): Long = Date.now().toLong()

internal actual fun localOffsetMinutes(): Int = runCatching { -Date().getTimezoneOffset() }.getOrDefault(0)
