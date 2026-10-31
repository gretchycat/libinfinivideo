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
    // A source-built upstream AAR can replace Maven without changing the adapter.
    val localVlc = providers.gradleProperty("libVlcAar").orNull
    if (localVlc == null) implementation("org.videolan.android:libvlc-all:3.7.7")
    else implementation(files(localVlc))
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
