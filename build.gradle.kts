// Root build file. Plugin versions are declared in gradle/libs.versions.toml.
//
// Note: there is deliberately no `org.jetbrains.kotlin.android` plugin here. AGP 9
// has built-in Kotlin support and rejects that plugin. See AGENTS.md.
buildscript {
    dependencies {
        // AGP's build classpath pulls transitives that carry published CVEs. They are
        // build-tool only and never ship in the APK, but pinning the fixed versions
        // here makes Gradle's highest-version-wins conflict resolution lift them.
        //
        // Do NOT add KGP or KSP here: those must stay at the versions AGP bundles, or
        // KSP fails with "unexpected jvm signature V". See AGENTS.md.
        classpath("org.bouncycastle:bcprov-jdk18on:1.85")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.85")
        classpath("org.apache.commons:commons-lang3:3.18.0")
        classpath("org.apache.httpcomponents:httpclient:4.5.13")
        classpath("org.bitbucket.b_c:jose4j:0.9.6")
        classpath("org.jdom:jdom2:2.0.6.1")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
