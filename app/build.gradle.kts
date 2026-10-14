import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Supabase URL + publishable key come from kavach.properties (git-ignored), never from source.
val kavachProps = Properties().apply {
    val f = rootProject.file("kavach.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun kavachProp(name: String): String = kavachProps.getProperty(name, "")

val localTestRelease = providers.gradleProperty("kavach.testRelease").orNull == "true"

android {
    namespace = "app.kavach.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kavach.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${kavachProp("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${kavachProp("supabase.publishableKey")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional device-targeted delivery; normal builds keep all supported ABIs.
        providers.gradleProperty("kavach.targetAbi").orNull?.let { abi ->
            require(abi == "arm64-v8a") { "Unsupported delivery ABI: $abi" }
            ndk { abiFilters += abi }
        }

    }

    // Optional release key (git-ignored kavach.properties): release.storeFile, release.storePassword,
    // release.keyAlias, release.keyPassword. Release tasks require it.
    val releaseKeyFile = kavachProp("release.storeFile")
    signingConfigs {
        if (releaseKeyFile.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(releaseKeyFile)
                storePassword = kavachProp("release.storePassword")
                keyAlias = kavachProp("release.keyAlias")
                keyPassword = kavachProp("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
            // Production releases require an owner key; test signing is explicit opt-in only.
            signingConfig = if (localTestRelease) signingConfigs.getByName("debug") else signingConfigs.findByName("release")
            if (localTestRelease) versionNameSuffix = "-test-release"
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Build metadata shipped by several kotlinx/Ktor jars; duplicates fail the merge
        resources.excludes += "/META-INF/versions/9/previous-compilation-data.bin"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Keep debug/test configuration usable without a release secret.
gradle.taskGraph.whenReady {
    if (!localTestRelease && allTasks.any { it.name.contains("Release", ignoreCase = true) && !it.name.startsWith("test") }) {
        require(kavachProp("release.storeFile").isNotBlank() &&
            listOf("release.storePassword", "release.keyAlias", "release.keyPassword").all { kavachProp(it).isNotBlank() }) {
            "Release signing is not configured. Set release.storeFile, release.storePassword, release.keyAlias and release.keyPassword in kavach.properties."
        }
    }
}
