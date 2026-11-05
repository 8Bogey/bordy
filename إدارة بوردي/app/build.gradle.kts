import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing credentials stay in the parent project's gitignored local.properties.
val parentLocal = Properties()
val parentLocalFile = rootProject.file("../local.properties")
if (parentLocalFile.exists()) {
    parentLocalFile.inputStream().use { parentLocal.load(it) }
}

fun signingProperty(key: String): String = parentLocal.getProperty(key) ?: ""

android {
    namespace = "com.bordy.admin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bordy.admin"
        minSdk = 30
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
        buildConfigField("String", "API_BASE_URL", "\"https://bordy-license.bordy-license.workers.dev\"")
    }

    signingConfigs {
        create("release") {
            val fileName = signingProperty("RELEASE_STORE_FILE").ifEmpty { "../bordy-release.jks" }
            val resolved = rootProject.file(if (fileName.startsWith("../")) fileName else "../$fileName")
            storeFile = resolved
            storePassword = signingProperty("RELEASE_STORE_PASSWORD")
            keyAlias = signingProperty("RELEASE_KEY_ALIAS").ifEmpty { "bordy" }
            keyPassword = signingProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
