import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun alexetProp(name: String): String? =
    (localProps.getProperty(name) ?: findProperty(name) as? String)?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.ander.alexet"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.ander.alexet"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 382
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
    }

    signingConfigs {
        val storeFilePath = alexetProp("ALEXET_STORE_FILE")
        val storePassword = alexetProp("ALEXET_STORE_PASSWORD")
        val keyAlias = alexetProp("ALEXET_KEY_ALIAS")
        val keyPassword = alexetProp("ALEXET_KEY_PASSWORD")
        if (
            storeFilePath != null &&
            storePassword != null &&
            keyAlias != null &&
            keyPassword != null
        ) {
            create("release") {
                storeFile = file(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Use release keystore when configured, fallback to debug signing for installable local release builds
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            } ?: run {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        getByName("debug") {
            isDebuggable = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.android.jvm.get()))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.adaptive.navigationsuite)
    implementation(libs.google.android.material)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.muxer)
    implementation(libs.androidx.media3.common)
    implementation(libs.accompanist.permissions)
    // Archive contents (7z, tar, zip with any name encoding); xz decodes 7z LZMA / LZMA2.
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    // On-device smart search (SigLIP 2 encoders); the model itself is imported by the user.
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    // Real org.json for unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))

    debugImplementation(libs.androidx.compose.ui.tooling)
}

val copyReleaseApk = tasks.register("copyReleaseApk") {
    description = "Copies release APK to the root project directory and optional extra dirs"
    doNotTrackState("Direct file copy to project root")
    doLast {
        val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apkFile = File(releaseDir, "app-release.apk").takeIf { it.exists() }
            ?: File(releaseDir, "app-release-unsigned.apk").takeIf { it.exists() }
        if (apkFile != null) {
            val rootApk = rootProject.layout.projectDirectory.file("Alexet-release.apk").asFile
            apkFile.copyTo(rootApk, overwrite = true)

            // Optional destinations from local.properties / gradle props (semicolon-separated dirs).
            // Example: alexet.copy.apk.dir=C:\\Users\\You\\Desktop
            val extraDirs = alexetProp("alexet.copy.apk.dir")
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            for (dirPath in extraDirs) {
                val dir = File(dirPath)
                if (dir.exists() || dir.mkdirs()) {
                    apkFile.copyTo(File(dir, "Alexet-release.apk"), overwrite = true)
                }
            }
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseApk)
}