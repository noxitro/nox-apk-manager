// インストールの E2E テスト(app/src/androidTest)が入れる、中身の無いアプリ。
//
// 実在のアプリを入れずに「新規 / 更新 / 取り消し / 署名違い / 版の巻き戻し」を通すため、
// 同じパッケージ名で 3 つの APK を作る。
//   v1    … versionCode 1、鍵 A
//   v2    … versionCode 2、鍵 A(v1 の正しい更新)
//   v2alt … versionCode 2、鍵 B(署名違いで失敗するはずの更新)
//
// 鍵 A / B はテスト専用の使い捨てで、**ビルドのたびに build/fixture-keys/ に作る**。
// リポジトリには鍵ファイルを置かない(公開リポジトリの pre-commit が鍵の拡張子を弾く。
// 秘密でなくても、鍵の形をしたものを公開の履歴に残さない)。
// 要るのは「v1 と v2 が同じ鍵」「v2alt だけ違う鍵」という関係だけで、鍵そのものは
// 毎回変わってよい。テストは毎回 fixture を消してから入れる。
// debug.keystore を使わないのも同じ理由で、端末や CI のランナーごとに中身が違うため。
plugins {
    alias(libs.plugins.android.application)
}

val fixtureKeyDir = layout.buildDirectory.dir("fixture-keys")

/** 使い捨ての鍵を作る。JDK の keytool を使う(Gradle を動かしている JDK に必ずある)。 */
val generateFixtureKeys = tasks.register("generateFixtureKeys") {
    val dir = fixtureKeyDir
    outputs.dir(dir)
    doLast {
        val out = dir.get().asFile.apply { mkdirs() }
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
        val keytool = File(System.getProperty("java.home"), "bin/$exe")
        for (key in listOf("a", "b")) {
            val store = File(out, "fixture-$key.jks")
            if (store.exists()) continue
            val process = ProcessBuilder(
                keytool.absolutePath, "-genkeypair",
                "-keystore", store.absolutePath, "-storetype", "PKCS12",
                "-storepass", "fixture", "-keypass", "fixture", "-alias", "fixture",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500",
                "-dname", "CN=nox-apk-manager test fixture $key",
            ).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "keytool が失敗した: $log" }
        }
    }
}

android {
    namespace = "com.noxitro.apkmanager.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noxitro.apkmanager.fixture"
        minSdk = 26
        // 無確認の更新(UPDATE_PACKAGES_WITHOUT_USER_ACTION)は、入れられる側の targetSdk が
        // 新しいことも条件にある。本体と揃えておく。
        targetSdk = 36
    }

    signingConfigs {
        for (key in listOf("a", "b")) {
            create("key${key.uppercase()}") {
                storeFile = fixtureKeyDir.get().file("fixture-$key.jks").asFile
                storePassword = "fixture"
                keyAlias = "fixture"
                keyPassword = "fixture"
            }
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("v1") {
            dimension = "version"
            versionCode = 1
            versionName = "1"
            signingConfig = signingConfigs.getByName("keyA")
        }
        create("v2") {
            dimension = "version"
            versionCode = 2
            versionName = "2"
            signingConfig = signingConfigs.getByName("keyA")
        }
        create("v2alt") {
            dimension = "version"
            versionCode = 2
            versionName = "2-alt"
            signingConfig = signingConfigs.getByName("keyB")
        }
    }

    buildTypes {
        // 使うのは release だけ。debug の型は自分の署名(debug.keystore)を持っていて、
        // 味付け(flavor)側の鍵より優先されてしまう。
        release {
            isMinifyEnabled = false
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

// 署名の検査と書き込みより先に鍵を作る。
tasks.matching { it.name.startsWith("validateSigning") || it.name.startsWith("package") }.configureEach {
    dependsOn(generateFixtureKeys)
}
