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
    implementation("org.opendaylight.yangtools:yang-model-api:3.0.0")
    implementation("org.opendaylight.yangtools:yang-model-export:3.0.0")
    implementation("org.opendaylight.yangtools:yang-model-util:3.0.0")
    implementation("org.opendaylight.yangtools:yang-parser-api:3.0.0")
    implementation("org.opendaylight.yangtools:yang-parser-impl:3.0.0")
    implementation("org.opendaylight.yangtools:yang-data-codec-gson:3.0.0")
    implementation("org.slf4j:slf4j-api:1.8.0-beta4")
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