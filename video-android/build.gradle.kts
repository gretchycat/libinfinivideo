plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.gretchycat.infinivideo.video.android"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api(project(":video-api"))
    api("io.github.gretchycat.libinfinistream:stream-core:0.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
