import dev.krtirtho.PluginAbility
import dev.krtirtho.PluginCapability
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.zipline.gradle.plugin)
    alias(libs.plugins.spotubeGradle)
}

spotubePlugin {
    name = "Piped"
    version = "0.0.1"
    apiVersion = "0.0.1"
    description = "YouTube Music audio + metadata (tracks, albums, artists, playlists, search) via a Piped instance you choose in the plugin settings (no default instance). Saved items sync to the account when online and stay cached on this device for offline use"
    author = "IceDBorn"
    capabilities = listOf(
        PluginCapability.NETWORK_REQUESTS,
        PluginCapability.PERSISTENT_STORAGE,
        PluginCapability.WEBVIEW
    )
    abilities = listOf(PluginAbility.AUDIO, PluginAbility.METADATA)
    license = "AGPL-3.0-or-later"
    contact = "https://github.com/IceDBorn/spotube-piped-plugin"
    repository = "https://github.com/IceDBorn/spotube-piped-plugin"
    bugs = "https://github.com/IceDBorn/spotube-piped-plugin/issues"
}

kotlin {
    applyDefaultHierarchyTemplate()

    js {
        browser {
            testTask { enabled = false }
        }
        // Node, not Karma: no Chrome needed, and it runs under nix.
        nodejs()
        binaries.executable()
    }

    // Tests only; the shipped plugin stays JS/Zipline.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.zipline.core)
            implementation(libs.spotube.plugin.interfaces)
            api(libs.semver)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

zipline {
    mainFunction.set("dev.icedborn.spotube_plugin_piped.main")
}

plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().yarnLockAutoReplace = true
}
