import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The example's settings come from the git-ignored local.properties in the repository root:
//   payriff.publishableKey=pr_pk_live_...  (the card flow's publishable key)
//   payriff.environment=SANDBOX            (SANDBOX or PRODUCTION)
//   payriff.region=AZ                      (AZ or SA; SA has no sandbox: SANDBOX payments go to production)
// They are compiled into the DEBUG BuildConfig only; release builds have no publishable key.
// The example holds no secret key: your server creates the order with its secret key (POST /api/v3/orders).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun localValue(key: String): String = localProperties.getProperty(key)?.trim().orEmpty()

/** A Java string literal for buildConfigField, escaped so any value is safe to embed. */
fun javaString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 || c.code > 0x7E -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

android {
    namespace = "com.payriff.example.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.payriff.example.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

    buildTypes {
        debug {
            buildConfigField("String", "PAYRIFF_ENVIRONMENT", javaString(localValue("payriff.environment").ifEmpty { "SANDBOX" }))
            buildConfigField("String", "PAYRIFF_REGION", javaString(localValue("payriff.region").ifEmpty { "AZ" }))
            buildConfigField("String", "PAYRIFF_PUBLISHABLE_KEY", javaString(localValue("payriff.publishableKey")))
        }
        release {
            buildConfigField("String", "PAYRIFF_ENVIRONMENT", "\"SANDBOX\"")
            buildConfigField("String", "PAYRIFF_REGION", "\"AZ\"")
            buildConfigField("String", "PAYRIFF_PUBLISHABLE_KEY", "\"\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The example is never published; sign release builds with the debug key so they can be installed.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":payriff"))
    implementation(libs.androidx.core.ktx)
    // BottomSheetDialogFragment, the dialogs and the Material 3 theme of the example; the SDK itself needs none of it.
    implementation(libs.material)

    testImplementation(libs.junit)
    // Android's org.json for the JVM unit tests (the paste parsing).
    testImplementation(libs.org.json)
}
