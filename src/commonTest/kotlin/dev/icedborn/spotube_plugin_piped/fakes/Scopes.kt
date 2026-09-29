package dev.icedborn.spotube_plugin_piped.fakes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** For harnesses without a session: no background refresh ever launches on it. */
val noSessionScope: CoroutineScope = CoroutineScope(Job())
