// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Shared SDK versions consumed by sub-modules via rootProject.ext (e.g. :conversational-ai)
extra["compileSdkVersion"] = 36
extra["minSdkVersion"] = 26