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

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
  }
  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.14"
  }
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

  implementation("com.google.dagger:hilt-android:2.52")
  ksp("com.google.dagger:hilt-compiler:2.52")

  implementation("androidx.room:room-runtime:2.7.2")
  implementation("androidx.room:room-ktx:2.7.2")
  ksp("androidx.room:room-compiler:2.7.2")

  implementation("androidx.work:work-runtime-ktx:2.9.0")

  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")

  implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))
  implementation("io.github.jan-tennert.supabase:postgrest-kt")
  implementation("io.github.jan-tennert.supabase:auth-kt")
  implementation("io.ktor:ktor-client-okhttp:3.0.2")

  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
  implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

  implementation("com.google.firebase:firebase-messaging:23.2.0")

  testImplementation("junit:junit:4.13.2")
  testImplementation("app.cash.turbine:turbine:1.1.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
  testImplementation("androidx.datastore:datastore-preferences:1.1.1")
}
