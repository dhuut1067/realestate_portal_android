import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val kakaoKey = (localConfig.getProperty("KAKAO_NATIVE_APP_KEY")
    ?: providers.gradleProperty("KAKAO_NATIVE_APP_KEY").orNull ?: "").trim().removeSurrounding("\"")
require(kakaoKey.isEmpty() || kakaoKey.matches(Regex("[A-Za-z0-9]+"))) { "Invalid KAKAO_NATIVE_APP_KEY" }

android {
    namespace = "kr.co.estate.portal"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.co.estate.portal"
        minSdk = 24
        targetSdk = 36
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoKey\"")
        versionCode = 2
        versionName = "1.0.0"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.kakao.maps.open:android:2.15.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

