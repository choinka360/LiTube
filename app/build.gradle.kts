import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
}

val tvOAuthProperties = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
fun tvOAuthValue(name: String): String = "\"" + tvOAuthProperties.getProperty(name, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    buildFeatures { buildConfig = true }
    namespace = "com.hhst.youtubelite"
    compileSdk = 36

    installation {
        installOptions.add("-t")
    }

    lint {
        disable.add("MissingTranslation")
        disable.add("ExtraTranslation")
        abortOnError = false
    }

    defaultConfig {
        applicationId = "com.hhst.litube"
        minSdk = 26
        targetSdk = 36
        versionCode = 220
        versionName = "v2.2.0"
        buildConfigField("String", "TV_GOOGLE_CLIENT_ID", tvOAuthValue("litube.google.clientId"))
        buildConfigField("String", "TV_GOOGLE_CLIENT_SECRET", tvOAuthValue("litube.google.clientSecret"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug {
            versionNameSuffix = " (TV Preview)"
            if (providers.gradleProperty("litubeValidation").isPresent) applicationIdSuffix = ".validation"
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "META-INF/services/javax.script.ScriptEngineFactory"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.viewmodel)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.newpipeextractor)
    implementation(libs.isoparser)
    implementation(libs.gson)
    implementation(libs.commons.io)
    implementation(libs.picasso)
    implementation(libs.media)
    implementation(libs.photoview)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.mmkv)
    implementation(libs.activity)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)
    implementation(libs.webkit)
    implementation(libs.viewpager2)
    implementation(libs.recyclerview)
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// Keep builds consistent between Windows and UTF-8 environments.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
