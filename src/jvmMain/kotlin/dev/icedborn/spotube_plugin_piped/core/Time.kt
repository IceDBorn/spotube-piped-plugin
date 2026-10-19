package dev.icedborn.spotube_plugin_piped.core

import java.util.TimeZone

internal actual fun epochMillis(): Long = System.currentTimeMillis()

internal actual fun localOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
