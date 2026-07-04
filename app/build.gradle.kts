plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

import java.util.Properties
import java.io.FileInputStream

// Export Room schemas (version 10 onward) into app/schemas so future migrations can be
// validated by Room at compile time and by MigrationTestHelper in tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.ziesche.peppolreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ziesche.peppolreader"
        minSdk = 28
        targetSdk = 35
        versionCode = 20
        versionName = "3.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Feature flag for the separate "Invoice Creator" mode (ZUGFeRD 2.x generation).
        // Set to "false" to completely hide the mode; the reader is unaffected.
        buildConfigField("boolean", "ENABLE_INVOICE_CREATOR", "true")
    }

    signingConfigs {
        create("release") {
            val keyStoreFile = rootProject.file("keystore.properties")
            if (keyStoreFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(keyStoreFile))
                
                storeFile = file("release.jks")
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    applicationVariants.all {
        val variant = this
        val baseName = if (variant.buildType.name == "release") "PeppolReader" else "app-debug"
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "$baseName-${variant.versionName}.apk"
            }
    }
}

/**
 * Builds APK + AAB for the release variant and archives both into
 * release-archive/<versionName>/ so previous versions are never overwritten.
 *
 * Run: gradlew :app:archiveRelease
 */
tasks.register("archiveRelease") {
    group = "release"
    description = "Assembles release APK + AAB and copies them into release-archive/<versionName>/"
    dependsOn("assembleRelease", "bundleRelease")
    doLast {
        val versionName = android.defaultConfig.versionName
            ?: error("versionName is not set in android.defaultConfig")
        val archiveDir = rootProject.file("release-archive/$versionName")
        archiveDir.mkdirs()
        project.copy {
            from(layout.buildDirectory.dir("outputs/apk/release")) {
                include("*.apk")
            }
            from(layout.buildDirectory.dir("outputs/bundle/release")) {
                include("*.aab")
                rename("app-release.aab", "PeppolReader-$versionName.aab")
            }
            into(archiveDir)
        }
        println("Archived release artifacts to: ${archiveDir.absolutePath}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)


    // Room Database
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // RecyclerView
    implementation(libs.androidx.recyclerview)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // MPAndroidChart
    implementation(libs.mpandroidchart)

    // PDFBox (ZUGFeRD/Factur-X embedded XML extraction)
    implementation(libs.pdfbox.android)

    // WorkManager (due-date reminder periodic worker)
    implementation(libs.androidx.work.runtime.ktx)
}