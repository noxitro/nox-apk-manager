plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.noxitro.apkmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noxitro.apkmanager"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 個人利用のローカルビルドのみ。縮小はしない。
            isMinifyEnabled = false
            // 署名鍵は用意しない(端末には debug 署名のビルドを入れる)。
            // OAuth の Android クライアントは debug 鍵の SHA-1 で登録する(docs/SETUP.md)。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric で Compose の画面を JVM 上で描くのに、テーマやマニフェストのリソースが要る。
            isIncludeAndroidResources = true
            all {
                // JDK 21(Android Studio 同梱の JBR)では、Robolectric が FileDescriptor の内部に触れられずに
                // 「Failed to interact with raw FileDescriptor internals」で落ちる。JDK 17 では無くても動く。
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    // 画面のテストを、エミュレータを使わずに JVM 上で走らせる(push のたびの単体テストに乗る)。
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // OS のインストール確認画面(別アプリ)のボタンを押すため。
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ── インストールの E2E テストに渡す APK ──────────────────────────
// :fixture が作る 3 つの APK(v1 / v2 / v2alt)を、計装テストの assets に入れる。
// テストはそれを取り出して、本物の PackageInstaller に入れさせる。
abstract class CopyFixtureApks : DefaultTask() {
    @get:InputFiles
    abstract val apks: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dir = File(out, "fixture").apply { mkdirs() }
        for (apk in apks.files) {
            // fixture-v1-release.apk → v1.apk
            val name = apk.name.removePrefix("fixture-").removeSuffix("-release.apk")
            apk.copyTo(File(dir, "$name.apk"), overwrite = true)
        }
    }
}

val copyFixtureApks = tasks.register<CopyFixtureApks>("copyFixtureApks") {
    dependsOn(":fixture:assembleRelease")
    val fixtureBuild = project(":fixture").layout.buildDirectory
    for (flavor in listOf("v1", "v2", "v2alt")) {
        apks.from(fixtureBuild.file("outputs/apk/$flavor/release/fixture-$flavor-release.apk"))
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(copyFixtureApks, CopyFixtureApks::outputDir)
    }
}
