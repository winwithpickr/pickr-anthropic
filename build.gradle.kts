plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group   = "com.winwithpickr"
version = "0.1.0"

repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/winwithpickr/*")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: "winwithpickr"
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(libs.pickr.engine)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockk)
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("pickr-anthropic")
            description.set("Anthropic Claude integration for pickr command extraction")
            url.set("https://github.com/winwithpickr/pickr-anthropic")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
        }
    }
}
