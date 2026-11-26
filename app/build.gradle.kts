plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drifly.condutor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.drifly.condutor"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["appLabel"] = "DriFly Condutor"

        // Somente arquiteturas de celular de verdade (MapLibre compila para
        // arm64-v8a/armeabi-v7a; x86 só serve emulador).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            // Keystore exclusiva do DriFly Condutor. O SHA-1 desta chave é o que
            // deve ser cadastrado em qualquer serviço (Play Console, mapas etc.).
            // Segredos nunca ficam no código: vêm de variáveis de ambiente ou do
            // arquivo local signing.env (ignorado pelo Git). Ver SIGNING.md.
            val ksFile = System.getenv("DRIFLY_CONDUTOR_KEYSTORE_FILE")
                ?.takeIf { it.isNotBlank() }?.let { file(it) }
                ?: file("drifly-condutor-release.jks")
            if (ksFile.exists()) {
                val envFile = System.getenv("DRIFLY_CONDUTOR_SIGNING_ENV_FILE")
                    ?.takeIf { it.isNotBlank() }?.let { file(it) }
                    ?: rootProject.file("signing.env")
                val fileVars = if (envFile.exists()) {
                    envFile.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
                } else emptyMap()
                fun secret(name: String): String =
                    System.getenv(name) ?: fileVars[name]
                        ?: throw GradleException("Assinatura: defina $name (env) ou crie signing.env. Veja SIGNING.md")
                storeFile = ksFile
                storePassword = secret("DRIFLY_CONDUTOR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DRIFLY_CONDUTOR_KEY_ALIAS")
                    ?: fileVars["DRIFLY_CONDUTOR_KEY_ALIAS"]
                    ?: "drifly_condutor"
                keyPassword = secret("DRIFLY_CONDUTOR_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            // A versão de publicação é atualizada pela Play Store, não pelo app.
            buildConfigField("boolean", "AUTO_UPDATE", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // APK de teste instalado por cabo continua com o auto-atualizador.
            buildConfigField("boolean", "AUTO_UPDATE", "true")
            val ksFile = System.getenv("DRIFLY_CONDUTOR_KEYSTORE_FILE")
                ?.takeIf { it.isNotBlank() }?.let { file(it) }
                ?: file("drifly-condutor-release.jks")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Aba Mapa 100% offline: MapLibre Native Android (OpenGL ES estável em Adreno 610).
    implementation("org.maplibre.gl:android-sdk:12.3.1")

    // Fechadura local por biometria (AndroidX, sem serviços do Google).
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
