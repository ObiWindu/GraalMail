dependencies {
    api(project(":core"))
    api(project(":provider-api"))
    implementation(libs.jackson.databind)
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation(libs.junit.jupiter)
}
