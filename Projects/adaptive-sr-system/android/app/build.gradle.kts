plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("com.google.devtools.ksp")
  id("com.google.dagger.hilt.android")
}

android {
  namespace = "com.adaptivesr"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.adaptivesr"
    minSdk = 28
    targetSdk = 34
    versionCode = 1
    versionName = "0.1"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
  }
  // No composeOptions block: org.jetbrains.kotlin.plugin.compose (2.0.20)
  // manages the Compose compiler version itself; a manual
  // kotlinCompilerExtensionVersion pin conflicts with it.
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.06.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.0")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.navigation:navigation-compose:2.7.7")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
  implementation("androidx.hilt:hilt-work:1.2.0")
  ksp("androidx.hilt:hilt-compiler:1.2.0")

  implementation("com.google.dagger:hilt-android:2.52")
  ksp("com.google.dagger:hilt-compiler:2.52")

  implementation("androidx.room:room-runtime:2.7.2")
  implementation("androidx.room:room-ktx:2.7.2")
  ksp("androidx.room:room-compiler:2.7.2")

  implementation("androidx.work:work-runtime-ktx:2.9.0")

  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  // Direct dep: AppModule references Aead/AndroidKeysetManager in signatures,
  // and transitive implementation deps are not compile-visible to the app.
  implementation("com.google.crypto.tink:tink-android:1.13.0")

  implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))
  implementation("io.github.jan-tennert.supabase:postgrest-kt")
  implementation("io.github.jan-tennert.supabase:auth-kt")
  implementation("io.ktor:ktor-client-okhttp:3.0.2")

  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
  implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

  implementation("com.google.firebase:firebase-messaging:23.2.0")

  // Matches the kotlinx-serialization-json Ktor 3.0.2 (supabase-kt 3.0.3's
  // engine) expects; explicit pin avoids Gradle resolving a newer minor.
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

  testImplementation("junit:junit:4.13.2")
  testImplementation("app.cash.turbine:turbine:1.1.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
  testImplementation("androidx.datastore:datastore-preferences:1.1.1")
  testImplementation("androidx.test:core:1.6.1")
  testImplementation("org.robolectric:robolectric:4.14.1")
}
