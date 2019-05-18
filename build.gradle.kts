import java.net.URL
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.21"
    id("com.intershop.gradle.jaxb") version "3.0.3"
}

group = "dev.robakowski"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

jaxb {
    javaGen {
        create("opcua-model") {
            setSchema(file("src/main/resources/UA Model Design.xsd"))
        }
    }
}