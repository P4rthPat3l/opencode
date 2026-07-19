import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
  kotlin("jvm") version "2.1.21"
  id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  implementation("com.google.code.gson:gson:2.13.2")
  testImplementation(kotlin("test"))
  intellijPlatform {
    intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    pluginVerifier()
  }
}

kotlin {
  jvmToolchain(21)
}

intellijPlatform {
  pluginConfiguration {
    id = "ai.opencode.plugin"
    name = providers.gradleProperty("pluginName")
    version = providers.gradleProperty("pluginVersion")
    description = "OpenCode for JetBrains IDEs using the p4rth-opencode managed runtime."
    vendor {
      name = "P4rth OpenCode"
      url = "https://github.com/P4rthPat3l/opencode"
    }
    ideaVersion {
      sinceBuild = providers.gradleProperty("pluginSinceBuild")
      // No upper bound: the plugin stays installable on current and all future IDE builds.
      // Compatibility with new majors is confirmed via the plugin verifier, not a hard cap.
      untilBuild = provider { null }
    }
  }
  pluginVerification {
    ides {
      recommended()
    }
  }
  signing {
    certificateChain.set(providers.environmentVariable("JETBRAINS_CERTIFICATE_CHAIN"))
    privateKey.set(providers.environmentVariable("JETBRAINS_PRIVATE_KEY"))
    password.set(providers.environmentVariable("JETBRAINS_PRIVATE_KEY_PASSWORD"))
  }
  publishing {
    token.set(providers.environmentVariable("JETBRAINS_PUBLISH_TOKEN"))
  }
}

tasks.test {
  useJUnitPlatform()
}

tasks.register("packageRuntimeRelease") {
  group = "distribution"
  description = "Package fork runtimes and generate the JetBrains runtime manifest"

  doLast {
    val releaseVersion = providers.gradleProperty("releaseVersion").orNull
      ?: error("Pass -PreleaseVersion=<version>")
    val releaseRepo = providers.gradleProperty("releaseRepo").orElse("P4rthPat3l/opencode").get()
    val releasePlatform = providers.gradleProperty("releasePlatform").orNull
    val root = projectDir.resolve("../..").canonicalFile
    val targets = listOf(
      arrayOf("linux-x64", "opencode-linux-x64", "opencode", "p4rth-opencode"),
      arrayOf("linux-arm64", "opencode-linux-arm64", "opencode", "p4rth-opencode"),
      arrayOf("darwin-x64", "opencode-darwin-x64", "opencode", "p4rth-opencode"),
      arrayOf("darwin-arm64", "opencode-darwin-arm64", "opencode", "p4rth-opencode"),
      arrayOf("windows-x64", "opencode-windows-x64", "opencode.exe", "p4rth-opencode.exe"),
      arrayOf("windows-arm64", "opencode-windows-arm64", "opencode.exe", "p4rth-opencode.exe"),
    )
    val selectedTargets = targets.filter { releasePlatform == null || it[0] == releasePlatform }
    require(selectedTargets.isNotEmpty()) { "Unsupported platform: $releasePlatform" }

    val destination = layout.buildDirectory.dir("release/$releaseVersion").get().asFile
    if (releasePlatform == null) destination.deleteRecursively()
    destination.mkdirs()

    selectedTargets.forEach { target ->
      val bin = root.resolve("packages/opencode/dist/${target[1]}/bin")
      val executable = bin.resolve(target[2])
      require(executable.isFile) { "Missing $executable. Build ${target[1]} before packaging." }
      val speechName = if (target[2].endsWith(".exe")) "opencode-speech.exe" else "opencode-speech"
      val speech = bin.resolve(speechName)
      require(speech.isFile) {
        "Missing $speech. Build packages/speech for this platform and re-run the opencode build with OPENCODE_SPEECH_DIR (or --single after cargo build --release)."
      }
      val filename = "p4rth-opencode-${target[0]}.zip"
      val archive = destination.resolve(filename)
      ZipOutputStream(archive.outputStream().buffered()).use { zip ->
        bin.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { file ->
          zip.putNextEntry(ZipEntry(if (file.name == target[2]) target[3] else file.name))
          file.inputStream().buffered().use { it.copyTo(zip) }
          zip.closeEntry()
        }
      }
      val sha256 = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
      logger.lifecycle("Packaged ${target[0]} (with $speechName): $sha256")
    }

    val artifacts = linkedMapOf<String, Map<String, Any>>()
    targets.forEach { target ->
      val filename = "p4rth-opencode-${target[0]}.zip"
      val archive = destination.resolve(filename)
      if (!archive.isFile) return@forEach
      artifacts[target[0]] = mapOf(
        "url" to "https://github.com/$releaseRepo/releases/download/v$releaseVersion/$filename",
        "sha256" to MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) },
        "size" to archive.length(),
      )
    }

    destination.resolve("p4rth-opencode-jetbrains-runtime.json").writeText(
      JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
        "pluginVersion" to project.version.toString(),
        "runtimeVersion" to releaseVersion,
        "product" to "p4rth-opencode",
        "protocolVersion" to 1,
        "jetbrainsBridgeVersion" to 1,
        "storageSchemaVersion" to 1,
        "artifacts" to artifacts,
      ))) + "\n",
    )
    logger.lifecycle("Release files: ${destination.absolutePath}")
    logger.lifecycle("Create release: gh release create v$releaseVersion '${destination.absolutePath}'/* --repo $releaseRepo --title $releaseVersion")
  }
}
