import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val stableSigningFile = System.getenv("YIJI_SIGNING_PROPERTIES")?.let { File(it) }
    ?: File(System.getProperty("user.home"), ".config/yiji/signing.properties")
val stableSigning = Properties().apply {
    if (stableSigningFile.isFile) stableSigningFile.inputStream().use { load(it) }
}
val stableStore = stableSigning.getProperty("storeFile")?.let {
    File(it).let { path -> if (path.isAbsolute) path else File(stableSigningFile.parentFile, it) }
}
val hasStableSigning = stableStore?.isFile == true &&
    listOf("storePassword", "keyAlias", "keyPassword").all { !stableSigning.getProperty(it).isNullOrBlank() }
if ((System.getenv("YIJI_REQUIRE_STABLE_SIGNING") == "true" || stableSigningFile.exists()) && !hasStableSigning) {
    throw GradleException("Fixed signing configuration is missing or incomplete; refusing to use a temporary signing key.")
}

android {
    namespace = "cn.yiji.app"
    compileSdk = 35
    // Optional SDK extension for local installations; no extension APIs are used.
    (project.findProperty("yiji.sdkExtension") as String?)?.toInt()?.let { compileSdkExtension = it }
    defaultConfig {
        applicationId = "cn.yiji.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "2.3.1"
        ndk.abiFilters += "arm64-v8a"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    if (hasStableSigning) {
        signingConfigs.create("yijiStable") {
            storeFile = stableStore
            storePassword = stableSigning.getProperty("storePassword")
            keyAlias = stableSigning.getProperty("keyAlias")
            keyPassword = stableSigning.getProperty("keyPassword")
            storeType = stableSigning.getProperty("storeType", "PKCS12")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("yijiStable")
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("yijiStable")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", project.findProperty("yiji.testRepo") as String? ?: "https://repo.maven.apache.org/maven2")
        }
    }
    kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    kapt("androidx.room:room-compiler:2.7.1")
    // Match the compiler's metadata reader without adding a runtime dependency.
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testRuntimeOnly("com.microsoft.onnxruntime:onnxruntime:1.23.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
val prepareQqRuntime by tasks.registering(Exec::class) {
    val runtime = rootProject.file("qq-runtime")
    inputs.files(fileTree(runtime) { include("tools/**", "bridge/**", "compile-stubs/**", "third_party/**", "login-dependencies.json", "prepare.py", "LICENSE", "THIRD_PARTY_NOTICES.md") })
    outputs.files(runtime.resolve("cache/acidify-android.jar"), runtime.resolve("cache/xmlutil-android.jar"))
    outputs.dir(runtime.resolve("generated-assets"))
    commandLine("python3", runtime.resolve("prepare.py"), android.sdkDirectory)
}
android.sourceSets.getByName("main").assets.srcDir(rootProject.file("qq-runtime/generated-assets"))
tasks.named("preBuild") { dependsOn(prepareQqRuntime) }
val qqRuntimeJars = files(rootProject.file("qq-runtime/cache/acidify-android.jar"), rootProject.file("qq-runtime/cache/xmlutil-android.jar")).builtBy(prepareQqRuntime)
dependencies { implementation(qqRuntimeJars) }
configurations.configureEach {
    exclude(group = "io.github.pdvrieze.xmlutil", module = "core-jvm")
    exclude(group = "io.github.pdvrieze.xmlutil", module = "core")
}
apply(from = "qq-dependencies.gradle.kts")

val prepareMessageModel by tasks.registering(Exec::class) {
    inputs.files(rootProject.file("model/deployment_manifest.json"), rootProject.file("app/src/main/assets/tinybert/metadata.json"), rootProject.file("scripts/prepare_message_model.py"))
    inputs.files(fileTree(rootProject.file("model")) { include("message-bert-*.part*") })
    outputs.file(file("src/main/assets/tinybert/model.onnx"))
    commandLine("python3", rootProject.file("scripts/prepare_message_model.py"))
}
tasks.named("preBuild") { dependsOn(prepareMessageModel) }
