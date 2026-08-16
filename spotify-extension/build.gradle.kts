plugins {
    id("com.android.library")
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "dev.brahmkshatriya.echo.spotify"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":common"))
    implementation(libs.protobuf.java)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
