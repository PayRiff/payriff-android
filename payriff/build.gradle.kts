import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

val sdkGroup = providers.gradleProperty("GROUP").get()
val sdkArtifact = providers.gradleProperty("POM_ARTIFACT_ID").get()
val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

group = sdkGroup
version = sdkVersion

android {
    namespace = "com.payriff.android"
    compileSdk = 36
    resourcePrefix = "payriff_"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        // The dependency versions are deliberate floors (see gradle/libs.versions.toml): host apps resolve
        // them to their own newer versions, and raising them would raise every host's compileSdk/AGP.
        // The toolchain (AGP) is pinned to the version verified for this repository. These two checks only
        // report "a newer version exists", so they are disabled instead of baselined.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    explicitApi()
    coreLibrariesVersion = libs.versions.kotlinCoreLibraries.get()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        allWarningsAsErrors.set(true)
    }
}

/** Writes the SDK version into an internal Kotlin constant, so no public BuildConfig class is published. */
abstract class GenerateSdkVersion : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDirectory.file("com/payriff/android/internal/SdkVersion.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.payriff.android.internal\n\n" +
                "/** Version of the Payriff Android SDK. Generated from VERSION_NAME in gradle.properties. */\n" +
                "internal const val SDK_VERSION: String = \"${versionName.get()}\"\n",
        )
    }
}

val generateSdkVersion = tasks.register<GenerateSdkVersion>("generateSdkVersion") {
    versionName.set(sdkVersion)
    outputDirectory.set(layout.buildDirectory.dir("generated/source/sdkVersion"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateSdkVersion, GenerateSdkVersion::outputDirectory)
    }
}

dependencies {
    // ActivityResultContract is part of the public API.
    api(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    coordinates(sdkGroup, sdkArtifact, sdkVersion)
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral()

    // Sign only when a key is provided (CI or ~/.gradle/gradle.properties), so local builds and
    // publishToMavenLocal work without one. Maven Central rejects unsigned uploads anyway.
    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent ||
        providers.gradleProperty("signing.gnupg.keyName").isPresent
    if (hasSigningKey) {
        signAllPublications()
    }

    pom {
        name.set("Payriff Android SDK")
        description.set(
            "Pay Payriff orders with a native card form: on-device card encryption, 3-D Secure in a native sheet, " +
                "only a publishable key in the app. Documentation: https://docs.payriff.com",
        )
        url.set("https://payriff.com")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("Payriff SDK License")
                url.set("https://github.com/PayRiff/payriff-android/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("payriff")
                name.set("Payriff")
                organization.set("Payriff")
                organizationUrl.set("https://payriff.com")
            }
        }
        scm {
            url.set("https://github.com/PayRiff/payriff-android")
            connection.set("scm:git:https://github.com/PayRiff/payriff-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/PayRiff/payriff-android.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/PayRiff/payriff-android/issues")
        }
    }
}
