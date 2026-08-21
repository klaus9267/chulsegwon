plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

application { mainClass.set("reach.MainKt") }

tasks.test { useJUnitPlatform() }

// 데이터·산출물 경로를 레포 루트 기준으로 쓰기 위해 (기본값은 builder/)
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
