extension {
    name = "extensions/aliexpress.mpe"
}

android {
    namespace = "app.hxreborn.extension"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 23
        buildConfigField("String", "BUNDLE_VERSION", "\"${rootProject.version}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.morphe.extensions.library)
}
