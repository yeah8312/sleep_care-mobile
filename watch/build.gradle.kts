plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val samsungHealthSensorSdkAar = layout.projectDirectory.file("libs/samsung-health-sensor-api.aar")

// Wear OS 워치 앱 모듈입니다.
// 휴대폰 명령을 받아 전면 서비스로 심박 추적을 수행하고 Data Layer로 샘플을 보냅니다.
android {
    namespace = "com.sleepcare.watch"
    compileSdk = 36

    defaultConfig {
        // Wear OS Data Layer는 휴대폰/워치 앱을 같은 applicationId와 같은 서명으로 묶어 같은 앱으로 판단합니다.
        // Kotlin namespace는 워치 코드 패키지와 R 경로를 유지하기 위해 `com.sleepcare.watch`로 그대로 둡니다.
        applicationId = "com.sleepcare.mobile"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val checkSamsungHealthSensorSdk by tasks.registering {
    group = "verification"
    description = "Verify that the local Samsung Health Sensor SDK AAR is available."

    doLast {
        if (!samsungHealthSensorSdkAar.asFile.exists()) {
            error(
                """
                Samsung Health Sensor SDK AAR이 필요합니다.
                Samsung Developer에서 SDK를 받은 뒤 다음 위치에 로컬로 배치하세요.
                ${samsungHealthSensorSdkAar.asFile.absolutePath}

                이 AAR은 라이선스/용량/로컬 설정 문제를 피하기 위해 Git에 커밋하지 않습니다.
                """.trimIndent(),
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(checkSamsungHealthSensorSdk)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    // 모바일 앱과 같은 워치 메시지 경로/모델/코덱을 사용합니다.
    implementation(project(":watch-contracts"))
    // Samsung Health Sensor SDK는 로컬 AAR로만 붙입니다. 없으면 위 preBuild 체크가 이유를 설명하고 중단합니다.
    implementation(files(samsungHealthSensorSdkAar.asFile))

    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
