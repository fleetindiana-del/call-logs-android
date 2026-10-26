import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// Google Maps Android API key. Kept out of version control: put
//   MAPS_API_KEY=AIza...
// in local.properties (gitignored), or export it as an env var on CI.
// Without it the map view renders grey and logs an authorization failure.
val localProps: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** local.properties first, then the environment, so CI can supply the same keys. */
fun secret(name: String): String? =
    localProps.getProperty(name) ?: System.getenv(name)

val mapsApiKey: String = secret("MAPS_API_KEY") ?: ""

/**
 * Release signing. Absent on a machine that has no keystore configured, in which
 * case the release build stays unsigned rather than failing — but do not ship
 * that build to a fleet handset.
 *
 * Signature identity matters more here than in a normal app: a Device Owner
 * provisioned handset blocks uninstall, so an APK signed with a *different* key
 * can never upgrade it. Changing or losing this key costs a factory reset per
 * phone. See docs/LOCATION-LOCKDOWN.md.
 */
val releaseStoreFile: String? = secret("RELEASE_STORE_FILE")

android {
    namespace = "com.company.callmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.company.callmonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Consumed by com.google.android.geo.API_KEY in AndroidManifest.xml
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
                // v1 as well as v2/v3: minSdk 24 predates v2-only verification,
                // and a fleet handset that cannot verify the APK cannot be
                // upgraded at all once uninstall is blocked.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Firebase (BoM pins compatible versions for all Firebase libs)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)

    // AutoStarter — OEM autostart / background permission deep-links
    implementation("com.github.judemanutd:autostarter:1.1.0")

    // Room — local database for GPS points and sessions
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Location — FusedLocationProviderClient
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Maps — Google Maps SDK for the in-app route track
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // EncryptedSharedPreferences for enrollment data
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Chrome Custom Tabs for expense portal
    implementation("androidx.browser:browser:1.8.0")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
