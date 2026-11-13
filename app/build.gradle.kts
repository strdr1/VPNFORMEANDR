plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.amsales.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amsales.vpn"
        minSdk = 24       // Android 7.0 — VpnService.addDisallowedApplication работает с API 21+
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"

        // Собираем APK только для ARM (arm64 + armv7). На x86-эмуляторе
        // запускать смысла нет — у юзера телефоны.
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // libbox.aar содержит libgojni.so для arm64/armv7 — упаковываем
    // штатно. Никаких standalone-бинарей в jniLibs больше нет.
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Постоянный ключ подписи: без него каждая сборка CI подписана случайным
    // debug-ключом раннера, и обновление поверх установленной версии не
    // встаёт. Файл и пароль приходят из секретов GitHub (см. build.yml);
    // локально без них release подписывается debug-ключом.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "amsales"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Compose BOM — единая версия всех Compose-библиотек
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON для парсинга sing-box-конфигов
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // CameraX — для QR-сканера
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit barcode scanning (для QR)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ZXing — для генерации QR
    implementation("com.google.zxing:core:3.5.3")

    // libbox — sing-box как Android-библиотека (JitPack)
    // Содержит Libbox.newCommandServer / CommandServer.startOrReloadService
    // + PlatformInterface (15 методов) — это и есть наш реальный движок.
    implementation("com.github.singbox-android:libbox:1.13.12")
}
