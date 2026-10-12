plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // KonanTarget type comes from the host project's Kotlin plugin (which is
    // already on the buildscript classpath via applyDefaultHierarchyTemplate),
    // so we don't need to declare it again here. The Kotlin DSL plugin
    // (`kotlin-dsl`) brings the Gradle API + Kotlin compiler transitively.
    implementation(gradleApi())
}
