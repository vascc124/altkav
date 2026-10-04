import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uk.noammm.kav"
    compileSdk = 36

    defaultConfig {
        // Own id so Kav+ installs next to upstream Kav instead of over it.
        applicationId = "uk.noammm.kav.plus"
        minSdk = 26
        targetSdk = 35
        // AltKav+ build numbers only rise: "<upstream major.minor>.p<n>" with n counting every AltKav+
        // release, since Updates.isNewer compares digit by digit. 2.1.p10 is based on upstream Kav 2.1.1.
        versionCode = 2111
        versionName = "2.1.p11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Kav+: when the bundled timetable was built, so TimetableUpdate only fetches a newer week.
        val bundled = file("src/main/assets/il.kav").takeIf { it.exists() }?.lastModified() ?: 0L
        buildConfigField("long", "TIMETABLE_BUILT_MS", "${bundled}L")
        // MapLibre's renderer is native code. Every phone Kav can reach is arm64;
        // x86_64 stays so the release APK still installs on the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release key lives outside the repo (keystore.properties next to android/); without it
    // release builds fall back to the local debug key like upstream.
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("plus") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the local debug key: there is no store listing, and an update
            // only installs over the previous one if both carry the same signature.
            signingConfig = signingConfigs.findByName("plus") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
