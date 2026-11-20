import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.shyfr.app"
    compileSdk = 36
    defaultConfig {
        // Commit, z którego zbudowano APK (CI podaje -PgitSha=...). Pokazywany w Ustawieniach, żeby dało się sprawdzić, z jakich źródeł jest ten plik.
        buildConfigField("String", "GIT_SHA", "\"${(project.findProperty("gitSha") as String? ?: "lokalny").trim().take(40)}\"")
        // Centralny adres backendu SHYFR (HTTPS): shyfr2ApiBaseUrl w gradle.properties albo -Pshyfr2ApiBaseUrl=https://... Pusty = backend jeszcze niewdrożony. Brak adresu w kodzie.
        buildConfigField("String", "SHYFR2_API_BASE_URL", "\"${(project.findProperty("shyfr2ApiBaseUrl") as String? ?: "").trim()}\"")
        // FCM: dane projektu Firebase tylko z parametrów budowania (-PfcmAppId, -PfcmApiKey, -PfcmProjectId, -PfcmSenderId). Puste = push wyłączony. Bez kluczy w repozytorium.
        for ((k, p) in listOf("FCM_APP_ID" to "fcmAppId", "FCM_API_KEY" to "fcmApiKey", "FCM_PROJECT_ID" to "fcmProjectId", "FCM_SENDER_ID" to "fcmSenderId"))
            buildConfigField("String", k, "\"${(project.findProperty(p) as String? ?: "").trim()}\"")
        applicationId = "com.shyfr.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 130
        versionName = "2.0.3-beta"
    }
    // Podpisanie wydania: utwórz keystore.properties (storeFile, storePassword, keyAlias, keyPassword). Plik nie trafia do repozytorium.
    val ksFile = rootProject.file("keystore.properties")
    signingConfigs {
        create("release") {
            if (ksFile.exists()) {
                val p = Properties()
                ksFile.inputStream().use { p.load(it) }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    // libsignal-client (Java) niesie też natywne biblioteki desktopowe; na Androidzie są zbędne (zob. README libsignal).
    packaging { resources { excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni_amd64.so", "libsignal_jni_amd64.dylib") } }
    lint { abortOnError = false }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    // libsignal-android wymaga desugaringu bibliotek Javy (inaczej checkDebugAarMetadata pada)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("com.lambdapioneer.argon2kt:argon2kt:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // krypto pomocnicze (Argon2id, HKDF, X25519)
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    // SHYFR 2.0: synchronizacja w tle i push (etap 41)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.firebase:firebase-messaging:24.0.3")   // wersja do potwierdzenia przy pierwszym buildzie; init ręczny, bez wtyczki google-services
    // E2EE: libsignal (AGPL-3.0), wersja z gradle.properties (libsignalVersion). Potrzebne oba artefakty: -android (natywne .so) i -client (klasy Java).
    val libsignalVersion = (project.findProperty("libsignalVersion") as String?)?.trim().orEmpty()
    require(libsignalVersion.isNotEmpty()) { "Brak libsignalVersion w gradle.properties" }
    implementation("org.signal:libsignal-android:$libsignalVersion")
    implementation("org.signal:libsignal-client:$libsignalVersion")
}
