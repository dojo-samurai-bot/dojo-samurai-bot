plugins {
    id("com.android.application")
}

android {
    namespace = "ru.dachafibonacci.timewidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.dachafibonacci.timewidget"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../signing/time-widget.jks")
            storePassword = "TimeWidget2026"
            keyAlias = "timewidget"
            keyPassword = "TimeWidget2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
