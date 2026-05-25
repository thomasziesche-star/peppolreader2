plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

import java.util.Properties
import java.io.FileInputStream

android {
    namespace = "com.ziesche.peppolreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ziesche.peppolreader"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            isMinifyEnabled = false
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
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    
    // MPAndroidChart
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // PDFBox (ZUGFeRD/Factur-X embedded XML extraction)
    implementation(libs.pdfbox.android)
}