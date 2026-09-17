plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.idearecorder.app"; compileSdk = 36
    defaultConfig { applicationId = "com.idearecorder.app"; minSdk = 26; targetSdk = 36; versionCode = 100; versionName = "1.0.0" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("debug") } }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
