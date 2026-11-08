import java.util.zip.ZipFile

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
// .gitignore does not prevent private resources from entering an APK.
val verifyNoPrivateInputs by tasks.registering {
    doLast {
        // Root .env is reserved for local release signing, never a source asset.
        listOf("secrets", "data", "artifacts/private").forEach { relative ->
            val input = rootProject.file(relative)
            require(!input.isFile && (!input.exists() || input.walkTopDown().none { it.isFile })) {
                "项目包含私有输入，请先移除：$relative"
            }
        }
        fileTree("src").files.filter { it.isFile }.forEach { input ->
            val path = input.relativeTo(projectDir).invariantSeparatorsPath
            require(input.extension.lowercase() !in setOf("csv", "db", "sqlite", "sqlite3", "enc", "jks", "keystore", "pem", "p12", "pfx")) {
                "源码目录不允许存放私有数据文件：$path"
            }
            if ("/assets/" in path || "/res/raw/" in path) {
                require(path == "src/main/assets/xposed_init" && input.readText().trim() == "com.zuozishi.callhelper.rear.NativeRearHook") {
                    "新增资源需要审核，当前只允许无敏感信息的 Xposed 入口：$path"
                }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyNoPrivateInputs) }
listOf("Debug", "Release").forEach { variant ->
    val verifyApk = tasks.register("verify${variant}PackagedPrivacy") {
        dependsOn("package$variant")
        doLast {
            val apks = fileTree(layout.buildDirectory.dir("outputs/apk/${variant.lowercase()}")) { include("*.apk") }.files
            require(apks.isNotEmpty()) { "未找到待检查的 APK" }
            apks.forEach { apk ->
                ZipFile(apk).use { archive ->
                    archive.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                        val path = entry.name.lowercase()
                        require(path.substringAfterLast('.', "") !in setOf("csv", "db", "sqlite", "sqlite3", "enc", "jks", "keystore", "pem", "p12", "pfx", "apk", "aab", "zip")) { "APK 包含私有数据或未经审核的容器文件" }
                        require(path.split('/').none { it in setOf("private", "secrets", ".git") } && !path.substringAfterLast('/').startsWith(".env")) { "APK 包含私有文件" }
                        val content = archive.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                        require(!Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])").containsMatchIn(content)) { "APK 疑似包含硬编码 Base64 AES-256 密钥，需要审核" }
                        if (path.startsWith("assets/")) {
                            require(path in setOf("assets/xposed_init", "assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm")) { "APK 包含未经审核的资源" }
                            if (path == "assets/xposed_init") {
                                require(archive.getInputStream(entry).bufferedReader().use { it.readText().trim() } == "com.zuozishi.callhelper.rear.NativeRearHook") { "Xposed 入口内容异常" }
                            }
                        }
                    }
                }
            }
        }
    }
    tasks.matching { it.name == "assemble$variant" }.configureEach { dependsOn(verifyApk) }
}
android {
    namespace = "com.zuozishi.callhelper"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.zuozishi.callhelper"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation("com.android.tools.build:apksig:9.3.3")
    testImplementation("org.json:json:20240303")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    compileOnly(files("libs/xposed-api82.jar"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    // Android 17 removed the reflected InputManager API used by stable Espresso.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha03")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
