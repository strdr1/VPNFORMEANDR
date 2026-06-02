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
        versionCode = 1
        versionName = "1.0.0"

        // Собираем APK только для ARM (arm64 + armv7). На x86-эмуляторе
        // запускать смысла нет — у юзера телефоны.
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // sing-box-бинарь лежит в jniLibs/<abi>/libsing-box.so — это «бинарь,
    // не библиотека». Чтобы Android при установке распаковал его на диск
    // (а не оставил внутри APK), просим extractNativeLibs=true.
    packaging {
        jniLibs {
            useLegacyPackaging = true   // = extractNativeLibs=true в манифесте
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Подпись — debug-кейстор Android SDK (по умолчанию). GitHub Actions
            // соберёт APK с этой подписью — ставится на любой Android вручную.
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            // Пока тоже debug-подпись — позже сменим на release-keystore.
            signingConfig = signingConfigs.getByName("debug")
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
}
