plugins {
    id("com.android.application")
    // START: FlutterFire Configuration
    id("com.google.gms.google-services")
    // END: FlutterFire Configuration
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.psc.nhkcoop"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.psc.nhkcoop"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // One flavor per environment. dev/staging get an applicationId suffix so all
    // three can be installed side by side on one device; prod keeps the bare id.
    // The app label is set per flavor via the ${appLabel} manifest placeholder.
    // Each flavor's google-services.json lives in android/app/src/<flavor>/ (the
    // gms plugin matches it by package name). They currently all point at the
    // nhk-coop-staging project as a placeholder — regenerate per env with
    // `flutterfire configure` once the real dev/staging/prod projects exist.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "NHK Coop (Dev)"
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            manifestPlaceholders["appLabel"] = "NHK Coop (Staging)"
        }
        create("prod") {
            dimension = "env"
            manifestPlaceholders["appLabel"] = "NHK Coop"
        }
    }
}

flutter {
    source = "../.."
}
