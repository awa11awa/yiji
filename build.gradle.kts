buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools:r8:9.5.28") }
}
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.kapt") version "2.4.10" apply false
}
