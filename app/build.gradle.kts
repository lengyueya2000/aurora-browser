import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 签名凭据来自根目录 keystore.properties（已 gitignore）。
//
// **release 构建缺凭据就直接失败**（见下面 buildTypes 里那段检查）。以前是「退化成一个
// 未签名的 APK」：`assembleRelease` 照样成功，产出的却是个装不上的包，而构建日志里只有一行
// 不显眼的警告。发布流程里最容易出错的就是这一步——签名证书换了，老用户就装不上更新
// （Android 只允许同一证书的包覆盖安装）。宁可让构建停下来。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

android {
    namespace = "dev.aurora.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aurora.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.17"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 资源裁剪。之前一直关着（`isMinifyEnabled = false`），理由是「怕把
            // 注入网页的 JS 接口混淆掉」——那个顾虑是真的，但正解是写 keep 规则
            // （见 proguard-rules.pro），而不是把整个优化关掉：关掉之后 APK 里带着全部
            // 类名、方法名和调试信息，体积大、反编译出来的代码几乎等于源码。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // 没有签名凭据就**不要产出 release 包**。退化成一个未签名的 APK 是最坏的结果：
                // 构建成功、日志里只有一行警告，而那个包在真机上根本装不上（或者更糟——被
                // 别的证书签了覆盖安装，把用户数据搞乱）。让构建在这里停住。
                throw GradleException(
                    "缺少 keystore.properties，无法构建 release。" +
                        "签名凭据在仓库根目录的 keystore.properties（已 gitignore），" +
                        "格式见 README 的「构建与签名」一节。",
                )
            }
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
        // 设置页要显示真实版本号，别再手写一份字符串（迟早和 versionName 漂移）。
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Robolectric 按 @Config 复用沙箱：同一个 JVM 里，sdk=34 的所有测试类共享一套 Looper /
// Choreographer / 静态状态。Compose 测试一旦留下没被回收的组合，这个沙箱就再也不会上报
// idle，于是同一批里其它 Compose 测试全部 AppNotIdleException（单独跑却是绿的）。
// 每个测试类一个新 JVM 把状态隔离开——线上只有一个进程，所以这不是掩盖问题。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    forkEvery = 1L
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)   // viewModel() 所需
    implementation(libs.androidx.lifecycle.runtime.compose)     // LifecycleEventEffect（主页「继续观看」）
    // 视频全屏 / 下载 / 投屏都交给 Media3 的 ExoPlayer：原生 Surface 渲染，不再走
    // WebView 的全屏自定义 View（真机上会黑屏），HLS 播放与离线缓存也由它负责。
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.database)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
