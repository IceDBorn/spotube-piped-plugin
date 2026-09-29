package dev.icedborn.spotube_plugin_piped.core

internal actual fun epochMillis(): Long = kotlin.js.Date.now().toLong()
