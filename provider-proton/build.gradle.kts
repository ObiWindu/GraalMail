dependencies {
    api(project(":provider-api"))
    api(project(":provider-imap"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(project(":testing"))
    testImplementation(libs.junit.jupiter)
}
