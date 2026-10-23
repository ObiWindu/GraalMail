import org.graalvm.buildtools.gradle.dsl.GraalVMExtension

plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.16"
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
    implementation("org.openjfx:javafx-base:22.0.1:linux")
    implementation("org.openjfx:javafx-graphics:22.0.1:linux")
    implementation("org.openjfx:javafx-controls:22.0.1:linux")
    implementation("org.openjfx:javafx-fxml:22.0.1")
    implementation("org.openjfx:javafx-media:22.0.1")
    implementation("org.openjfx:javafx-web:22.0.1:linux")
    implementation(libs.jackson.databind)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.junit.jupiter)
}

// Remove non-classifier JavaFX jars from distribution (they lack module descriptors)
// Keep javafx-media as it's required by javafx.web and has no linux variant
tasks.named("installDist").configure {
    doLast {
        val libDir = layout.buildDirectory.dir("install/graalmail/lib").get().asFile
        val nonModuleJars = listOf(
            "javafx-base-22.0.1.jar",
            "javafx-controls-22.0.1.jar",
            "javafx-graphics-22.0.1.jar",
            "javafx-web-22.0.1.jar"
        )
        nonModuleJars.forEach { name ->
            val file = libDir.resolve(name)
            if (file.exists()) {
                file.delete()
                println("Removed non-module jar: $name")
            }
        }
    }
}

application {
    applicationName = "graalmail"
    mainClass.set("com.graalmail.desktop.GraalMailApp")
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

val appVersion = "0.1.0"
val iconFile = layout.projectDirectory.file("src/main/resources/icons/graalmail.png")

/**
 * Copies the runtime of the build JDK into a runtime image.
 *
 * The image is used by jpackage. A linked image would be smaller, but jlink
 * refuses to run on a JDK whose security configuration was changed by the
 * vendor, which is the case on many distributions. The copy works on every
 * JDK, so the packaging does not depend on the build machine.
 */
val prepareRuntimeImage by tasks.registering(Sync::class) {
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
val packageAppImage by tasks.registering(Exec::class) {
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
        "--dest", destDir.get().asFile.absolutePath
    )
}

/**
 * Stages the desktop entry and the application icon so that they can be
 * installed into the desktop environment of the user.
 */
val stageDesktopFiles by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Stages the desktop entry and the application icon."
    from(layout.projectDirectory.file("src/main/resources/packaging/graalmail.desktop")) {
        into("share/applications")
    }
    from(layout.projectDirectory.file("src/main/resources/icons/graalmail.png")) {
        into("share/icons/hicolor/256x256/apps/graalmail.png")
    }
    from(layout.projectDirectory.file("src/main/resources/icons/graalmail-64.png")) {
        into("share/icons/hicolor/64x64/apps/graalmail.png")
    }
    into(layout.buildDirectory.dir("dist/desktop-files"))
}

/**
 * Packages the native binary into a Debian package.
 */
val packageDeb by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a Debian package with jpackage."
    dependsOn("nativeCompile")
    val nativeExe = layout.buildDirectory.file("native/nativeCompile/graalmail")
    val destDir = layout.buildDirectory.dir("dist/deb")
    val icon = iconFile.asFile
    inputs.file(nativeExe)
    outputs.dir(destDir)
    doFirst { delete(destDir) }
    commandLine(
        "jpackage",
        "--type", "deb",
        "--name", "GraalMail",
        "--app-version", appVersion,
        "--input", layout.buildDirectory.dir("native/nativeCompile").get().asFile.absolutePath,
        "--icon", icon.absolutePath,
        "--dest", destDir.get().asFile.absolutePath
    )
}

/**
 * Packages the native binary into an RPM package.
 */
val packageRpm by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds an RPM package with jpackage."
    dependsOn("nativeCompile")
    val destDir = layout.buildDirectory.dir("dist/rpm")
    val icon = iconFile.asFile
    doFirst { delete(destDir) }
    commandLine(
        "jpackage",
        "--type", "rpm",
        "--name", "GraalMail",
        "--app-version", appVersion,
        "--input", layout.buildDirectory.dir("native/nativeCompile").get().asFile.absolutePath,
        "--icon", icon.absolutePath,
        "--dest", destDir.get().asFile.absolutePath
    )
}

/**
 * Packages the native binary into an AppImage.
 */
val packageAppImageNative by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds an AppImage with jpackage."
    dependsOn("nativeCompile")
    val destDir = layout.buildDirectory.dir("dist/appimage")
    val icon = iconFile.asFile
    doFirst { delete(destDir) }
    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "GraalMail",
        "--app-version", appVersion,
        "--input", layout.buildDirectory.dir("native/nativeCompile").get().asFile.absolutePath,
        "--icon", icon.absolutePath,
        "--dest", destDir.get().asFile.absolutePath
    )
}
