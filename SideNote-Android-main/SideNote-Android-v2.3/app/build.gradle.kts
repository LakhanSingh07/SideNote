plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.sidedeck.notes"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sidedeck.notes"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "3.0.0-design-implementation-source"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
