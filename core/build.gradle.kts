dependencies {
    api(libs.slf4j.api)
    api(libs.jsoup)
    api("org.web3j:core:5.0.0") {
        // A single modern BouncyCastle is provided by :provider-proton;
        // the ancient jdk15on line would otherwise clash with it on Android
        // and shadow it on the desktop class path.
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    api("com.upokecenter:cbor:4.5.2")
    testImplementation(libs.junit.jupiter)
}
