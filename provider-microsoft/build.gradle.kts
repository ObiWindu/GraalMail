dependencies {
    api(project(":provider-api"))
    api(project(":provider-imap"))
    implementation(libs.jakarta.mail)
    implementation(libs.angus.mail)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(project(":testing"))
    testImplementation(libs.junit.jupiter)
}
