plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
fun envString(name:String,fallback:String=""):String = "\""+(System.getenv(name) ?: fallback).replace("\\","\\\\").replace("\"","\\\"")+"\""
android {
    buildFeatures {buildConfig=true}

    namespace = "com.sidedeck.notes"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sidedeck.notes"
        minSdk = 26
        targetSdk = 36
        versionCode = 32
        versionName = "3.2.0-preview"
        buildConfigField("String","API_BASE",envString("SIDENOTE_API_BASE"))
        buildConfigField("String","LICENSE_PUBLIC_KEY",envString("SIDENOTE_LICENSE_PUBLIC_KEY"))
        buildConfigField("String","PLAY_PRODUCT",envString("SIDENOTE_PLAY_PRODUCT","sidenote_pro"))
        buildConfigField("String","PLAY_MONTHLY_PLAN",envString("SIDENOTE_PLAY_MONTHLY_PLAN","monthly"))
        buildConfigField("String","PLAY_YEARLY_PLAN",envString("SIDENOTE_PLAY_YEARLY_PLAN","yearly"))
        buildConfigField("boolean","ANDROID_FREE_BETA","true")
        buildConfigField("boolean","COMMERCIAL_BUILD",if(System.getenv("SIDENOTE_COMMERCIAL_BUILD")=="1")"true" else "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if(System.getenv("SIDENOTE_ANDROID_KEYSTORE")!=null)create("production"){
            storeFile=file(System.getenv("SIDENOTE_ANDROID_KEYSTORE"));storePassword=System.getenv("SIDENOTE_ANDROID_STORE_PASSWORD")
            keyAlias=System.getenv("SIDENOTE_ANDROID_KEY_ALIAS");keyPassword=System.getenv("SIDENOTE_ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes { release { isMinifyEnabled = false;if(System.getenv("SIDENOTE_ANDROID_KEYSTORE")!=null)signingConfig=signingConfigs.getByName("production") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
