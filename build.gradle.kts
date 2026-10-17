// Root build file. Plugin versions are declared in gradle/libs.versions.toml.
//
// Note: there is deliberately no `org.jetbrains.kotlin.android` plugin here. AGP 9
// has built-in Kotlin support and rejects that plugin. See AGENTS.md.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
