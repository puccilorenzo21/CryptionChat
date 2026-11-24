import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val keystorePropertiesFile = project.rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
} else {
    println("keystore.properties not found. Skipping release signing config.")
}

android {
    namespace = "com.lollo1344.cryptionchat"
    compileSdk = 37

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.getProperty("storeFile") != null) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.lollo1344.cryptionchat"
        minSdk = 29
        targetSdk = 34
        versionCode = 78
        versionName = "78.6 (beta)"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resourceConfigurations.addAll(listOf("it", "en"))

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++11", "-O3", "-fvisibility=hidden", "-fdata-sections", "-ffunction-sections")
                arguments(
                    "-DANDROID_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }

            ndk {
                debugSymbolLevel = "NONE"
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
        viewBinding = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"

            excludes += "**/*.prof"
            excludes += "**/*.profm"
            excludes += "META-INF/dexopt/**"
        }
    }
}

dependencies {
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Google Location Services
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.media:media:1.8.0")

    // WebRTC
    implementation("io.github.webrtc-sdk:android:144.7559.09")

    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Room Database (Salvataggio locale messaggi)
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    implementation("net.zetetic:sqlcipher-android:4.9.0@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Glide
    implementation("com.github.bumptech.glide:glide:5.0.7")
    implementation("com.github.bumptech.glide:okhttp3-integration:5.0.7")
    kapt("com.github.bumptech.glide:compiler:5.0.7")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // Crypto
    implementation("androidx.security:security-crypto:1.1.0")

    // Compressor (Immagini)
    implementation("id.zelory:compressor:3.0.1")

    // Transcoder (Compressione Video)
    implementation("com.otaliastudios:transcoder:0.11.2")

    // Biometric
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")

    // Flexbox
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    // Editor Immagini
    implementation("com.burhanrashid52:photoeditor:3.1.0")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    // UCrop
    implementation("com.github.yalantis:ucrop:2.2.11")

    // QR CODE (ZXing)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")

    // Trascrizione Audio Offline (Vosk)
    implementation("com.alphacephei:vosk-android:0.3.75")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    // API Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}