import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val systemJar = rootProject.file("deps/android-system.jar")

android {
    namespace = "com.smartvowificall.ims"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.smartvowificall.ims"
        minSdk = 33
        targetSdk = 37
        versionCode = 17
        versionName = "0.4.6-dev"
    }

    signingConfigs {
        create("localDev") {
            storeFile = rootProject.file("local-signing.p12")
            storeType = "PKCS12"
            storePassword = "local-development"
            keyAlias = "smartvowificall"
            keyPassword = "local-development"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("localDev")
        }
        debug {
            signingConfig = signingConfigs.getByName("localDev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

gradle.projectsEvaluated {
    tasks.withType<JavaCompile>().configureEach {
        // Put android-system.jar ahead of android.jar so hidden telephony/IMS APIs resolve.
        classpath = files(systemJar) + classpath
    }
}

dependencies {
    compileOnly(files("../deps/android-system.jar"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")

    // Miuix HyperOS / MIUIX Compose UI library (0.9.4)
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")

    // Kyant0 AndroidLiquidGlass / Backdrop real-time Liquid Glass refraction (2.0.1)
    implementation("io.github.kyant0:backdrop-android:2.0.1")
}
