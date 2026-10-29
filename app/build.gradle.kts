import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

fun getReleaseProperty(key: String): String {
    return (project.findProperty(key) as? String)
        ?: localProperties.getProperty(key)
        ?: ""
}

android {
    namespace = "com.bordy.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bordy.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        buildConfigField("String", "API_BASE_URL", "\"https://bordy-license.bordy-license.workers.dev\"")
        buildConfigField("String", "API_FALLBACK_URL", "\"https://bordy-license.bordy-license.workers.dev\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY_K1", "\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERQomeD+6BCo9741IttIh1I72X+faB17168Hn66b1hgiOuOWjLiMiLwxhwgfW6xC9eQVVbyzSGh8oPHZZ4TiAiQ==\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY_K2", "\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEu+of+mUwnnBBaSQ5HY3CuPwURC7xyV99GgwcUL/iF+1sIRxS9ej4+66DB+nELfWkV5faRXgu/51SIpcUhRIfAw==\"")
        buildConfigField("String", "EXPECTED_CERT_SHA256", "\"5D:10:D0:27:79:DC:B9:70:2E:C8:9B:E0:85:F5:7D:B9:8F:A8:C3:FE:14:AA:25:5C:A4:81:A5:2A:19:3F:38:76\"")
    }

    signingConfigs {
        create("release") {
            val fileName = getReleaseProperty("RELEASE_STORE_FILE").ifEmpty { "bordy-release.jks" }
            storeFile = if (file(fileName).exists()) file(fileName) else rootProject.file(fileName)
            storePassword = getReleaseProperty("RELEASE_STORE_PASSWORD")
            keyAlias = getReleaseProperty("RELEASE_KEY_ALIAS").ifEmpty { "bordy" }
            keyPassword = getReleaseProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            (project.findProperty("apiBaseUrl") as? String)?.let { url ->
                buildConfigField("String", "API_BASE_URL", "\"$url\"")
            }
        }
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

    lint {
        // تجميد الدين الحالي؛ أي تحذير جديد يفشل البناء
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        // فحوص ترقية الاعتماديات تتغيّر مع الشبكة ولا تخصّ صحة الكود
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "UseTomlInstead")
    }
}

ksp {
    // تصدير مخططات Room إلى app/schemas لتفعيل اختبارات الترقية
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
