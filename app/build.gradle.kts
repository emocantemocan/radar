import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.radarnav"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radarnav.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.2"

        // MapLibre yerel kütüphaneleri: telefonlar (arm) ve emülatör (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Repoya eklenmiş sabit debug anahtarı: her CI derlemesi aynı imzayı taşır,
        // böylece güncellemeler eski sürümü kaldırmadan kurulabilir.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Kişisel kurulum (sideload) için hata ayıklama anahtarıyla imzalanır
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }

    lint {
        abortOnError = false
    }

    testOptions {
        // Robolectric: Android kaynakları ve manifest testlerde kullanılabilsin
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        // Türkçe yerel ayarda bazı kütüphaneler "LINUX".lowercase() -> "lınux" yapıp yerel kütüphaneyi bulamıyor
        unitTests.all { it.jvmArgs("-Duser.language=en", "-Duser.country=US") }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")

    // Vektör harita (telefon + Android Auto): MapLibre, veri OpenFreeMap (anahtarsız)
    implementation("org.maplibre.gl:android-sdk:11.8.8")

    // Anlık ve doğru konum (Google Play Hizmetleri yoksa yerleşik GPS kullanılır)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Android Auto ekranlarını araba/telefon olmadan test etmek için
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.car.app:app-testing:1.4.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
