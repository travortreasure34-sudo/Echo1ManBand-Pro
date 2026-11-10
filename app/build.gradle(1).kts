plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.echo1.manbandpro"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.echo1.manbandpro"
        minSdk = 24; targetSdk = 34
        versionCode = 1; versionName = "0.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.appcompat:appcompat:1.6.1") }
