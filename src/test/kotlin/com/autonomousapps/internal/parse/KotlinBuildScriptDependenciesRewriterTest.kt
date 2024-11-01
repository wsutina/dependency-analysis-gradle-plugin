package com.autonomousapps.internal.parse

import com.autonomousapps.internal.advice.AdvicePrinter
import com.autonomousapps.internal.advice.DslKind
import com.autonomousapps.model.Advice
import com.autonomousapps.model.Coordinates
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class KotlinBuildScriptDependenciesRewriterTest {
  @TempDir
  lateinit var dir: Path

  @Test fun `can update dependencies`() {
    // Given
    val sourceFile = dir.resolve("build.gradle.kts")
    sourceFile.writeText(
      """
        import foo
        import bar

        plugins {
          id("foo")
        }

        repositories {
          google()
          mavenCentral()
        }

        apply(plugin = "bar")

        extra["magic"] = 42

        android {
          whatever
        }

        dependencies {
          implementation("heart:of-gold:1.+")
          api(project(":marvin"))
          testImplementation("pan-galactic:gargle-blaster:2.0-SNAPSHOT") {
              because("life's too short not to")
          }
        }

        println("hello, world!")
      """.trimIndent()
    )
    val advice = setOf(
      Advice.ofChange(Coordinates.of(":marvin"), "api", "compileOnly"),
      Advice.ofRemove(Coordinates.of("pan-galactic:gargle-blaster:2.0-SNAPSHOT"), "testImplementation"),
      Advice.ofAdd(Coordinates.of(":sad-robot"), "runtimeOnly"),
    )

    // When
    val parser = KotlinBuildScriptDependenciesRewriter.of(sourceFile, advice, AdvicePrinter(DslKind.KOTLIN))

    // Then
    assertThat(parser.rewritten().trimmedLines()).containsExactlyElementsIn(
      """
        import foo
        import bar

        plugins {
          id("foo")
        }

        repositories {
          google()
          mavenCentral()
        }

        apply(plugin = "bar")

        extra["magic"] = 42

        android {
          whatever
        }

        dependencies {
          implementation("heart:of-gold:1.+")
          compileOnly(project(":marvin"))
          runtimeOnly(project(":sad-robot"))
        }

        println("hello, world!")
      """.trimIndent().trimmedLines()
    )
  }

  @Test fun `ignores buildscript dependencies`() {
    // Given
    val sourceFile = dir.resolve("build.gradle.kts")
    sourceFile.writeText(
      """
      import foo
      import bar

      // see https://github.com/dropbox/dropbox-sdk-java/blob/master/build.gradle
      buildscript {
        extra["foo"] = "ba/r"
        fizzle()
        repositories {
          google()
          maven { url = uri("https://plugins.gradle.org/m2/") }
        }
        dependencies {
          classpath("com.github.ben-manes:gradle-versions-plugin:0.27.0")
          classpath(files("gradle/dropbox-pem-converter-plugin"))
        }
      }

      plugins {
        id("foo")
      }

      repositories {
        google()
        mavenCentral()
      }

      apply(plugin = "bar")

      extra["magic"] = 42

      android {
        whatever
      }

      dependencies {
        implementation("heart:of-gold:1.+")
        api(project(":marvin"))
        testImplementation("pan-galactic:gargle-blaster:2.0-SNAPSHOT") {
          because("life's too short not to")
        }
      }

      println("hello, world!")
    """.trimIndent()
    )
    val advice = setOf(
      Advice.ofChange(Coordinates.of(":marvin"), "api", "compileOnly"),
      Advice.ofRemove(Coordinates.of("pan-galactic:gargle-blaster:2.0-SNAPSHOT"), "testImplementation"),
      Advice.ofAdd(Coordinates.of(":sad-robot"), "runtimeOnly"),
    )

    // When
    val parser = KotlinBuildScriptDependenciesRewriter.of(
      sourceFile,
      advice,
      AdvicePrinter(DslKind.KOTLIN),
    )

    // Then
    assertThat(parser.rewritten().trimmedLines()).containsExactlyElementsIn(
      """
      import foo
      import bar

      // see https://github.com/dropbox/dropbox-sdk-java/blob/master/build.gradle
      buildscript {
        extra["foo"] = "ba/r"
        fizzle()
        repositories {
          google()
          maven { url = uri("https://plugins.gradle.org/m2/") }
        }
        dependencies {
          classpath("com.github.ben-manes:gradle-versions-plugin:0.27.0")
          classpath(files("gradle/dropbox-pem-converter-plugin"))
        }
      }

      plugins {
        id("foo")
      }

      repositories {
        google()
        mavenCentral()
      }

      apply(plugin = "bar")

      extra["magic"] = 42

      android {
        whatever
      }

      dependencies {
        implementation("heart:of-gold:1.+")
        compileOnly(project(":marvin"))
        runtimeOnly(project(":sad-robot"))
      }

      println("hello, world!")
      """.trimIndent().trimmedLines()
    )
  }

  private fun Path.writeText(text: String): Path = Files.writeString(this, text)
  private fun String.trimmedLines() = lines().map { it.trimEnd() }
}
