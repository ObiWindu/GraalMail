plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    api(project(":provider-api"))
    api(project(":security"))
    api(libs.junit.jupiter)
    implementation(libs.greenmail)
    implementation(libs.greenmail.junit5)
}
