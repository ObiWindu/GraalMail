dependencies {
    api(project(":provider-api"))
    implementation(libs.jakarta.mail)
    implementation(libs.angus.mail)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(project(":testing"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.greenmail)
    testImplementation(libs.greenmail.junit5)
}
