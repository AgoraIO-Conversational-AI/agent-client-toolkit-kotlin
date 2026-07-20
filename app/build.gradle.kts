import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.navigation.safe.args)
}

val buildTimestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.reader(Charsets.UTF_8).use { localProperties.load(it) }
}
val agentBackendUrl = localProperties
    .getProperty("agent.backend.url", "http://10.0.2.2:8000")
    .trim()

android {
    namespace = "io.agora.agent.toolkit"
    compileSdk = 36

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "io.agora.agent.toolkit.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        fun buildConfigString(name: String, value: String) {
            val escapedValue = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            buildConfigField("String", name, "\"$escapedValue\"")
        }

        buildConfigString("AGENT_BACKEND_URL", agentBackendUrl)
    }

    signingConfigs {
        create("release") {
            storeFile = project.file("agora_test.jks")
            storePassword = "agoratest123"
            keyAlias = "agora"
            keyPassword = "agoratest123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    lint {
        // targetSdk 36 is the latest stable Android (16). Newer installed
        // platforms (37/Canary) are previews we intentionally do not target.
        disable += "OldTargetApi"
    }

    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                val flavorPrefix = "Agora_Agent_Client_Toolkit_Demo_for_Android"
                val packageName = applicationId.replace('.', '_')
                (this as BaseVariantOutputImpl).outputFileName =
                    "${flavorPrefix}_${packageName}_v${versionName.orEmpty()}_${buildTimestamp}.apk"
            }
        }
    }
}

dependencies {
    // Use the local toolkit module by default for development and sample validation.
    implementation(project(":conversational-ai"))

    // Published Maven Toolkit verification: comment the local project dependency above,
    // then uncomment this line after the target version is available in the Maven repo.
    // implementation("io.agora.agents:agora-agent-client-toolkit:2.9.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.gson)

    // Agora SDKs
    implementation(libs.agora.rtc.full)
    implementation(libs.agora.rtm.lite)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle components
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)

    // RecyclerView
    implementation(libs.androidx.recyclerview)

    // Navigation Component
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
}
