import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// 正式签名：密钥库在 keystore/（.gitignore 挡着，绝不入库），口令在同目录之外的 keystore.properties。
// 找不到 keystore.properties 或密钥库文件时（比如别人 clone 下来），就不配置签名 —— release 退化成未签名包，
// 但编译本身不受影响。这样别人也能 build，只是产不出能覆盖安装的包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasReleaseKey = releaseStoreFile != null && releaseStoreFile.exists()

// 有 keystore.properties 却找不到密钥库，直接报错停下 —— 否则会静悄悄地打出一个未签名包，
// 装上去了才发现（0.1.401 就这么踩过一次：密钥库被挪到仓库外，release 变成未签名）。
if (keystorePropsFile.exists() && !hasReleaseKey) {
    throw GradleException(
        "keystore.properties 存在，但它指向的密钥库找不到：$releaseStoreFile\n" +
            "要么把 .jks 放回这个路径，要么删掉 keystore.properties（那样只会打出未签名包）。"
    )
}

android {
    namespace = "com.aris.emojichan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aris.emojichan"
        minSdk = 26
        targetSdk = 34
        // 版本号规则：0.<阶段>.<构建序号> —— 每构建一版把最后一段加一（0.2.000 → 0.2.001）。
        // versionCode 取「阶段 × 1000 + 构建序号」，跟 versionName 对得上，装新版才能覆盖旧版；
        // 0.1.x 阶段的 versionCode 最高到 416，所以 0.2.000 从 2000 起算。
        // APK 文件名会带上 versionName。
        versionCode = 2202
        versionName = "0.2.202"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 暂时不开：本项目没有反射/动态取资源，开了大概率没问题，但没实测过就不在发布版上冒险。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式密钥签名：换了密钥，装过旧版（debug 签名）的机器必须先卸载，数据会丢（allowBackup=false）。
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // 产物带上版本号：以前叫 app-debug.apk，每版都覆盖同名文件，根本分不清哪一版是新的。
    applicationVariants.all {
        val appVersion = versionName
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "emojichan-v" + appVersion + "-" + name + ".apk"
        }
    }
}

// Robolectric 首次运行会自行下载 android-all jar（约 163 MB）。默认源是 maven 中央仓库，
// 本机实测只有约 1 MB/s 且会长时间卡住；改用阿里云镜像（实测 100 MB/s 以上）。
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.id", "aliyun")
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.glide)
    ksp(libs.glide.ksp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}