import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.gradle.api.tasks.bundling.Compression

plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.16"
}

/**
 * The JavaFX artifacts are published with a classifier for every supported
 * platform. The classifier of the build machine has to be used, otherwise an
 * x86_64 application image is created on an ARM64 machine (and vice versa).
 */
val javafxVersion = libs.versions.javafx.get()
val javafxClassifier = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arch == "aarch64" || arch == "arm64") "mac-aarch64" else "mac"
        arch == "aarch64" || arch == "arm64" -> "linux-aarch64"
        else -> "linux"
    }
}
val platformArch = run {
    val arch = System.getProperty("os.arch").lowercase()
    when (arch) {
        "x86_64", "amd64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> arch
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":provider-api"))
    implementation(project(":storage"))
    implementation(project(":sync"))
    implementation(project(":security"))
    implementation(project(":provider-imap"))
    implementation(project(":provider-gmail"))
    implementation(project(":provider-microsoft"))
    implementation(project(":provider-yahoo"))
    implementation(project(":provider-proton"))
    listOf("base", "graphics", "controls", "fxml", "media", "web").forEach { module ->
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxClassifier")
    }
    implementation(libs.jackson.databind)
    implementation(libs.playwright)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
}

application {
    applicationName = "graalmail"
    mainClass.set("com.graalmail.desktop.GraalMailApp")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

val graalvmNative = extensions.getByType(GraalVMExtension::class.java)
graalvmNative.binaries.all {
    imageName.set("graalmail")
    mainClass.set("com.graalmail.desktop.GraalMailApp")
}

tasks.named<Jar>("jar") {
    archiveFileName.set("graalmail.jar")
    manifest.attributes["Main-Class"] = "com.graalmail.desktop.GraalMailApp"
}

val appVersion = project.version.toString()
val iconFile = layout.projectDirectory.file("src/main/resources/icons/graalmail.png")

/**
 * Copies the runtime of the build JDK into a runtime image.
 *
 * The image is used by jpackage. A linked image would be smaller, but jlink
 * refuses to run on a JDK whose security configuration was changed by the
 * vendor, which is the case on many distributions. The copy works on every
 * JDK, so the packaging does not depend on the build machine.
 */
val prepareRuntimeImage = tasks.register<Sync>("prepareRuntimeImage") {
    group = "distribution"
    description = "Copies the runtime of the build JDK into a runtime image."
    val home = File(System.getProperty("java.home"))
    from(File(home, "bin")) {
        exclude("*.exe")
        into("bin")
    }
    from(File(home, "lib")) {
        exclude("src.zip")
        exclude("*.jmod")
        exclude("*.jmods")
        into("lib")
    }
    from(File(home, "conf")) {
        into("conf")
    }
    from(File(home, "legal")) {
        into("legal")
    }
    from(File(home, "release")) {
        into("release")
    }
    into(layout.buildDirectory.dir("runtime-image"))
}

/**
 * Builds a self contained Linux application image with jpackage. The image
 * contains the runtime, the application and a desktop entry, so it can be
 * started without a Java installation.
 */
val packageAppImage = tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "Builds a self contained Linux application image with jpackage."
    dependsOn("installDist", "prepareRuntimeImage")
    val runtimeDir = layout.buildDirectory.dir("runtime-image")
    val inputDir = layout.buildDirectory.dir("install/graalmail")
    val destDir = layout.buildDirectory.dir("dist/app-image")
    val icon = iconFile.asFile
    inputs.dir(inputDir)
    inputs.file(icon)
    outputs.dir(destDir)
    doFirst {
        delete(destDir)
    }
    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "GraalMail",
        "--app-version", appVersion,
        "--input", inputDir.get().asFile.absolutePath,
        "--runtime-image", runtimeDir.get().asFile.absolutePath,
        "--main-jar", "lib/graalmail.jar",
        "--main-class", "com.graalmail.desktop.GraalMailApp",
        "--icon", icon.absolutePath,
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--dest", destDir.get().asFile.absolutePath
    )
}

/**
 * Archives the application image created by {@link #packageAppImage} as a
 * release archive. The archive contains the self contained application, so it
 * can be unpacked and started without a Java installation.
 */
val packageAppImageTarball = tasks.register<Tar>("packageAppImageTarball") {
    group = "distribution"
    description = "Builds the application image and archives it as a tar.gz."
    dependsOn(packageAppImage)
    archiveFileName.set("graalmail-$appVersion-linux-$platformArch.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    compression = Compression.GZIP
    from(layout.buildDirectory.dir("dist/app-image/GraalMail")) {
        into("GraalMail")
    }
}

/**
 * Stages the desktop entry and the application icon so that they can be
 * installed into the desktop environment of the user.
 */
val stageDesktopFiles = tasks.register<Copy>("stageDesktopFiles") {
    group = "distribution"
    description = "Stages the desktop entry and the application icon."
    from(layout.projectDirectory.file("src/main/resources/packaging/graalmail.desktop")) {
        into("share/applications")
    }
    from(layout.projectDirectory.file("src/main/resources/icons/graalmail.png")) {
        into("share/icons/hicolor/256x256/apps")
        rename { "graalmail.png" }
    }
    from(layout.projectDirectory.file("src/main/resources/icons/graalmail-64.png")) {
        into("share/icons/hicolor/64x64/apps")
        rename { "graalmail.png" }
    }
    into(layout.buildDirectory.dir("dist/desktop-files"))
    doFirst {
        delete(layout.buildDirectory.dir("dist/desktop-files"))
    }
}

/**
 * Packages the application into a Debian package with jpackage.
 */
val packageDeb = tasks.register<Exec>("packageDeb") {
    group = "distribution"
    description = "Builds a Debian package with jpackage."
    dependsOn("installDist", "prepareRuntimeImage")
    val runtimeDir = layout.buildDirectory.dir("runtime-image")
    val inputDir = layout.buildDirectory.dir("install/graalmail")
    val destDir = layout.buildDirectory.dir("dist/deb")
    val icon = iconFile.asFile
    inputs.dir(inputDir)
    inputs.file(icon)
    outputs.dir(destDir)
    doFirst { delete(destDir) }
    commandLine(
        "jpackage",
        "--type", "deb",
        "--name", "GraalMail",
        "--app-version", appVersion,
        "--input", inputDir.get().asFile.absolutePath,
        "--runtime-image", runtimeDir.get().asFile.absolutePath,
        "--main-jar", "lib/graalmail.jar",
        "--main-class", "com.graalmail.desktop.GraalMailApp",
        "--icon", icon.absolutePath,
        "--linux-package-name", "graalmail",
        "--linux-menu-group", "Network",
        "--vendor", "GraalMail",
        "--description", "Mail client for Gmail, Outlook, Yahoo, Proton Mail and IMAP",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--dest", destDir.get().asFile.absolutePath
    )
}

/**
 * Packages the application into an RPM package with jpackage.
 */
val packageRpm = tasks.register<Exec>("packageRpm") {
    group = "distribution"
    description = "Builds an RPM package with jpackage."
    dependsOn("installDist", "prepareRuntimeImage")
    val runtimeDir = layout.buildDirectory.dir("runtime-image")
    val inputDir = layout.buildDirectory.dir("install/graalmail")
    val destDir = layout.buildDirectory.dir("dist/rpm")
    val icon = iconFile.asFile
    inputs.dir(inputDir)
    inputs.file(icon)
    outputs.dir(destDir)
    doFirst { delete(destDir) }
    commandLine(
        "jpackage",
        "--type", "rpm",
        "--name", "GraalMail",
        "--app-version", appVersion,
        "--input", inputDir.get().asFile.absolutePath,
        "--runtime-image", runtimeDir.get().asFile.absolutePath,
        "--main-jar", "lib/graalmail.jar",
        "--main-class", "com.graalmail.desktop.GraalMailApp",
        "--icon", icon.absolutePath,
        "--linux-package-name", "graalmail",
        "--linux-menu-group", "Network",
        "--vendor", "GraalMail",
        "--description", "Mail client for Gmail, Outlook, Yahoo, Proton Mail and IMAP",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--dest", destDir.get().asFile.absolutePath
    )
}
