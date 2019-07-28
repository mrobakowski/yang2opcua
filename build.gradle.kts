import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.21"
    id("com.intershop.gradle.jaxb") version "3.0.4"
    application
}

group = "dev.robakowski"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClassName = "dev.robakowski.yang2opcua.MainKt"
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("com.github.ajalt:clikt:2.1.0")

    implementation("org.opendaylight.yangtools:yang-model-api:3.0.0")
    implementation("org.opendaylight.yangtools:yang-model-export:3.0.0")
    implementation("org.opendaylight.yangtools:yang-model-util:3.0.0")
    implementation("org.opendaylight.yangtools:yang-parser-api:3.0.0")
    implementation("org.opendaylight.yangtools:yang-parser-impl:3.0.0")
    implementation("org.opendaylight.yangtools:yang-data-codec-gson:3.0.0")

    implementation("javax.xml.bind:jaxb-api:2.2.11")
    implementation("com.sun.xml.bind:jaxb-core:2.2.11")
    implementation("com.sun.xml.bind:jaxb-impl:2.2.11")
    implementation("javax.activation:activation:1.1.1")

    implementation("org.slf4j:slf4j-api:1.8.0-beta4")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    dependsOn("jaxb")
}

jaxb {
    javaGen {
        create("opcua-model") {
            setSchema(file("src/main/resources/UA Model Design.xsd"))
        }

        create("opcua-types") {
            setSchema(file("src/main/resources/UA Types.xsd"))
        }
    }
}