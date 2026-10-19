package dev.icedborn.spotube_plugin_piped.core

/** Wall clock in epoch millis; JS actual uses Date.now(). */
internal expect fun epochMillis(): Long

/** Minutes the local time zone is ahead of UTC, or 0 when the platform cannot tell. */
internal expect fun localOffsetMinutes(): Int
