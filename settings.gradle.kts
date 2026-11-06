pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "RadarNav"

// Android'den bağımsız iş mantığı (her yerde derlenip test edilebilir)
include(":core")

// Android uygulaması yalnızca Android SDK bulunduğunda dahil edilir.
// Böylece SDK olmayan ortamlarda da `gradle :core:test` çalışır.
val sdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (sdkAvailable) {
    include(":app")
} else {
    println("Android SDK bulunamadı: yalnızca :core modülü yapılandırılıyor.")
}
