dependencies {
    api(project(":core"))
    api(project(":provider-api"))
    api(project(":storage"))
    api(project(":security"))
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    testImplementation(libs.junit.jupiter)
}
