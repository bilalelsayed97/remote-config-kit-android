plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.bilalelsayed97.remoteconfigkit"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "remote-config-kit"
            afterEvaluate { from(components["release"]) }
            pom {
                name = "Remote Config Kit"
                description = "Android remote config client backed by a public GitHub JSON file."
                url = "https://github.com/bilalelsayed97/remote-config-kit-android"
                developers {
                    developer {
                        id = "bilalelsayed97"
                        name = "Bilal Elsayed"
                    }
                }
                scm {
                    url = "https://github.com/bilalelsayed97/remote-config-kit-android"
                    connection = "scm:git:https://github.com/bilalelsayed97/remote-config-kit-android.git"
                }
            }
        }
    }
}
