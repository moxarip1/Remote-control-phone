import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.time.Duration

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.smarthomecloud.kxzqpm"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "2.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

tasks.withType<Test> {
  failFast = true
  timeout.set(Duration.ofMinutes(2))
  testLogging {
    events("passed", "failed", "skipped")
  }
}

// Automatically sync the latest compiled debug APK to /debug/debug.apk on every build & future update
val rootDebugDir = rootProject.layout.projectDirectory.dir("debug").asFile
val fallbackBuiltApk = rootProject.layout.projectDirectory.file(".build-outputs/app-debug.apk").asFile
val moduleBuildDir = layout.buildDirectory

val syncDebugApkToRootFolder by tasks.registering {
  val targetDebugDir = rootDebugDir
  val fallbackApk = fallbackBuiltApk
  val buildDirProvider = moduleBuildDir
  outputs.upToDateWhen { false }
  doLast {
    if (!targetDebugDir.exists()) {
      targetDebugDir.mkdirs()
    }
    val buildDirFile = buildDirProvider.get().asFile
    val builtApksInBuildDir = if (buildDirFile.exists()) {
      buildDirFile.walkTopDown()
        .filter { it.isFile && it.extension == "apk" && !it.name.contains("androidTest", ignoreCase = true) }
        .toList()
    } else {
      emptyList()
    }

    // Always prefer the freshly packaged APK from buildDir over the previous cached .build-outputs APK
    val sourceApk = builtApksInBuildDir.maxByOrNull { it.lastModified() }
      ?: fallbackApk.takeIf { it.exists() && it.length() > 0L }

    if (sourceApk != null) {
      val debugApkDest = File(targetDebugDir, "debug.apk")
      val namedApkDest = File(targetDebugDir, "SmartHomeCloud-debug.apk")
      sourceApk.copyTo(debugApkDest, overwrite = true)
      sourceApk.copyTo(namedApkDest, overwrite = true)
      File(targetDebugDir, "BUILD_INFO.txt").writeText(
        "Application: Smart Home Cloud v2.0 (Single APK)\n" +
          "Version: 2.0 (versionCode = 2)\n" +
          "Updated At (Epoch ms): ${System.currentTimeMillis()}\n" +
          "BuildDir Path: ${buildDirFile.absolutePath}\n" +
          "Found APKs in BuildDir: ${builtApksInBuildDir.joinToString { "${it.absolutePath} (${it.length()}B)" }}\n" +
          "Selected Source APK: ${sourceApk.absolutePath} (${sourceApk.length()} bytes)\n" +
          "Included Updates:\n" +
          "- Email/Password Login, Account Creation & Password Reset + Google Sign-In\n" +
          "- Global WebRTC P2P Architecture (RTCDataChannel + 30 FPS Live Stream via users/{uid}/signaling)\n" +
          "- Dual Screen Mirroring (/screenshot Polling + WebRTC SurfaceViewRenderer)\n" +
          "- Optional & Skippable POST_NOTIFICATIONS Permission (Silent Background Server Mode without crash)\n"
      )
    }
  }
}

tasks.configureEach {
  if (
    name == "assembleDebug" ||
    name == "packageDebug" ||
    name == "createDebugApkListingFileRedirect"
  ) {
    finalizedBy(syncDebugApkToRootFolder)
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Firebase Auth, Firestore, Realtime Database & Google Sign-In via Credential Manager
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.database)
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)

  // Coroutines & Networking
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.okhttp)

  // Local HTTP Server (NanoHTTPD), Media3 ExoPlayer Streaming & WebRTC Live Screen Mirroring
  implementation(libs.nanohttpd)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)
  implementation("org.webrtc:google-webrtc:1.0.32006")

  // Unused dependencies commented out for APK size optimization:
  // implementation(libs.accompanist.permissions)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  // implementation(libs.androidx.datastore.preferences)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
