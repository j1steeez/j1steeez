pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Prefer Liferay for AndroidUSBCamera 3.3.3 (JitPack tag build is incomplete).
        maven(url = "https://repository.liferay.com/nexus/content/repositories/public/")
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "UsbVision"
include(":app")
