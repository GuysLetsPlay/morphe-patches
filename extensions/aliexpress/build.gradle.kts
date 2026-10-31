extension {
    name = "extensions/aliexpress.mpe"
}

android {
    namespace = "app.morphe.extension.aliexpress"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.annotation)
}
