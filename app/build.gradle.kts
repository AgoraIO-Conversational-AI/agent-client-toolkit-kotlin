import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.navigation.safe.args)
}

// Load env.example.properties as demo defaults, then override with local env.properties.
val envDefaultProperties = Properties()
val envDefaultPropertiesFile = rootProject.file("env.example.properties")
if (envDefaultPropertiesFile.exists()) {
    envDefaultPropertiesFile.reader(Charsets.UTF_8).use { envDefaultProperties.load(it) }
}

val envProperties = Properties()
envProperties.putAll(envDefaultProperties)
val localEnvProperties = Properties()
val envPropertiesFile = rootProject.file("env.properties")
if (envPropertiesFile.exists()) {
    envPropertiesFile.reader(Charsets.UTF_8).use { localEnvProperties.load(it) }
    envProperties.putAll(localEnvProperties)
}

// Validate required Agora configuration properties.
// The token toolbox host and demo ASR / LLM / TTS values are configurable
// through env.properties so the demo can validate different startup payloads.
val requiredProperties = listOf(
    "APP_ID"
)

val missingProperties = mutableListOf<String>()
requiredProperties.forEach { key ->
    val value = localEnvProperties.getProperty(key)
    if (value.isNullOrEmpty()) {
        missingProperties.add(key)
    }
}

if (missingProperties.isNotEmpty()) {
    val errorMessage = buildString {
        append("Please configure the following required properties in env.properties:\n")
        missingProperties.forEach { prop ->
            append("  - $prop\n")
        }
        append("\nPlease refer to env.example.properties for configuration reference.")
    }
    throw GradleException(errorMessage)
}


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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        fun buildConfigString(name: String, value: String) {
            val escapedValue = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            buildConfigField("String", name, "\"$escapedValue\"")
        }

        fun buildConfigStringFromEnv(name: String, defaultValue: String = "") {
            buildConfigString(name, envProperties.getProperty(name, defaultValue))
        }

        fun buildConfigNumber(type: String, name: String) {
            val value = envProperties.getProperty(name)
            if (value.isNullOrBlank()) {
                throw GradleException("Please configure $name in env.example.properties or env.properties")
            }
            buildConfigField(type, name, value)
        }

        // Credentials must come from local env.properties, not example placeholders.
        buildConfigString("APP_ID", localEnvProperties.getProperty("APP_ID").orEmpty())
        buildConfigString("APP_CERTIFICATE", localEnvProperties.getProperty("APP_CERTIFICATE").orEmpty())
        buildConfigStringFromEnv("TOOLBOX_SERVER_HOST")
        buildConfigStringFromEnv("ASR_VENDOR")
        buildConfigStringFromEnv("ASR_API_KEY")
        buildConfigStringFromEnv("ASR_MODEL")
        buildConfigStringFromEnv("LLM_URL")
        buildConfigStringFromEnv("LLM_API_KEY")
        buildConfigStringFromEnv("LLM_MODEL")
        buildConfigStringFromEnv("TTS_VENDOR")
        buildConfigStringFromEnv("TTS_KEY")
        buildConfigStringFromEnv("TTS_MODEL_ID")
        buildConfigStringFromEnv("TTS_VOICE_ID")
        buildConfigNumber("int", "TTS_SAMPLE_RATE")
    }

    buildTypes {
        release {
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
}

dependencies {
    implementation(project(":conversational-ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    testImplementation(libs.json)
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
