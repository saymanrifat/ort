/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

import org.eclipse.jgit.ignore.FastIgnoreRule

import org.jetbrains.gradle.ext.Gradle
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    // Apply third-party plugins.
    alias(libs.plugins.dependencyAnalysis)
    alias(libs.plugins.ideaExt)
    alias(libs.plugins.versions)
}

// Only override a default version (which usually is "unspecified"), but not a custom version.
if (version == Project.DEFAULT_VERSION) {
    val gitVersionProvider = providers.of(GitVersionValueSource::class) { parameters { workingDir = rootDir } }
    version = gitVersionProvider.get()
}

logger.lifecycle("Building ORT version $version.")

idea {
    project {
        settings {
            runConfigurations {
                // Disable "condensed" multi-line diffs when running tests from the IDE via Gradle run configurations to
                // more easily accept actual results as expected results.
                defaults(Gradle::class.java) {
                    jvmArgs = "-Dkotest.assertions.multi-line-diff=simple"
                }
            }
        }
    }
}

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    gradleReleaseChannel = "current"
    outputFormatter = "json"

    val nonFinalQualifiers = listOf(
        "alpha", "b", "beta", "cr", "dev", "ea", "eap", "m", "milestone", "pr", "preview", "rc", "\\d{14}"
    ).joinToString("|", "(", ")")

    val nonFinalQualifiersRegex = Regex(".*[.-]$nonFinalQualifiers[.\\d-+]*", RegexOption.IGNORE_CASE)

    rejectVersionIf {
        candidate.version.matches(nonFinalQualifiersRegex)
    }
}

// Gradle's "dependencies" task selector only executes on a single / the current project [1]. However, sometimes viewing
// all dependencies at once is beneficial, e.g. for debugging version conflict resolution.
// [1]: https://docs.gradle.org/current/userguide/viewing_debugging_dependencies.html#sec:listing_dependencies
tasks.register("allDependencies") {
    val dependenciesTasks = allprojects.map { it.tasks.named<DependencyReportTask>("dependencies") }
    dependsOn(dependenciesTasks)

    // Ensure deterministic output by requiring to run tasks after each other in always the same order.
    dependenciesTasks.zipWithNext().forEach { (a, b) ->
        b.configure {
            mustRunAfter(a)
        }
    }
}

val copyrightExcludedPaths = listOf(
    "LICENSE",
    "NOTICE",
    "batect",
    "gradlew",
    "gradle/",
    "examples/",
    "integrations/completions/",
    "plugins/reporters/asciidoc/src/main/resources/pdf-theme/pdf-theme.yml",
    "plugins/reporters/asciidoc/src/main/resources/templates/freemarker_implicit.ftl",
    "plugins/reporters/fossid/src/main/resources/templates/freemarker_implicit.ftl",
    "plugins/reporters/freemarker/src/main/resources/templates/freemarker_implicit.ftl",
    "plugins/reporters/static-html/src/main/resources/prismjs/",
    "plugins/reporters/web-app-template/yarn.lock",
    "resources/META-INF/",
    "resources/exceptions/",
    "resources/licenses/",
    "resources/licenserefs/",
    "test/assets/",
    "funTest/assets/"
)

val copyrightExcludedExtensions = listOf(
    "css",
    "graphql",
    "json",
    "md",
    "png",
    "svg",
    "ttf"
)

fun getCopyrightableFiles(rootDir: File): List<File> {
    val gitFilesProvider = providers.of(GitFilesValueSource::class) { parameters { workingDir = rootDir } }

    return gitFilesProvider.get().filter { file ->
        val isHidden = file.toPath().any { it.toString().startsWith(".") }

        !isHidden
                && copyrightExcludedPaths.none { it in file.invariantSeparatorsPath }
                && file.extension !in copyrightExcludedExtensions
    }
}

val maxCopyrightLines = 50

fun extractCopyrights(file: File): List<String> {
    val copyrights = mutableListOf<String>()

    var lineCounter = 0

    file.useLines { lines ->
        lines.forEach { line ->
            if (++lineCounter > maxCopyrightLines) return@forEach
            val copyright = line.replaceBefore(" Copyright ", "", "").trim()
            if (copyright.isNotEmpty() && !copyright.endsWith("\"")) copyrights += copyright
        }
    }

    return copyrights
}

val copyrightPrefixRegex = Regex("Copyright .*\\d{2,}(-\\d{2,})? ", RegexOption.IGNORE_CASE)

fun extractCopyrightHolders(statements: Collection<String>): List<String> {
    val holders = mutableListOf<String>()

    statements.mapNotNullTo(holders) { statement ->
        val holder = statement.replace(copyrightPrefixRegex, "")
        holder.takeUnless { it == statement }?.trim()
    }

    return holders
}

val checkCopyrightsInNoticeFile by tasks.registering {
    val files = getCopyrightableFiles(rootDir)
    val noticeFile = rootDir.resolve("NOTICE")
    val genericHolderPrefix = "The ORT Project Authors"

    inputs.files(files)

    doLast {
        val allCopyrights = mutableSetOf<String>()
        var hasViolations = false

        files.forEach { file ->
            val copyrights = extractCopyrights(file)
            if (copyrights.isNotEmpty()) {
                allCopyrights += copyrights
            } else {
                hasViolations = true
                logger.error("The file '$file' has no Copyright statement.")
            }
        }

        val notices = noticeFile.readLines()
        extractCopyrightHolders(allCopyrights).forEach { holder ->
            if (!holder.startsWith(genericHolderPrefix) && notices.none { holder in it }) {
                hasViolations = true
                logger.error("The '$holder' Copyright holder is not captured in '$noticeFile'.")
            }
        }

        if (hasViolations) throw GradleException("There were errors in Copyright statements.")
    }
}

val lastLicenseHeaderLine = "License-Filename: LICENSE"

val expectedCopyrightHolder =
    "The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)"

// The header without `lastLicenseHeaderLine` as that line is used as a marker.
val expectedLicenseHeader = """
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    SPDX-License-Identifier: Apache-2.0
""".trimIndent()

fun extractLicenseHeader(file: File): List<String> {
    var headerLines = file.useLines { lines ->
        lines.takeWhile { !it.endsWith(lastLicenseHeaderLine) }.toList()
    }

    while (true) {
        val uniqueColumnChars = headerLines.mapNotNullTo(mutableSetOf()) { it.firstOrNull() }

        // If there are very few different chars in a column, assume that column to consist of comment chars /
        // indentation only.
        if (uniqueColumnChars.size < 3) {
            val trimmedHeaderLines = headerLines.mapTo(mutableListOf()) { it.drop(1) }
            headerLines = trimmedHeaderLines
        } else {
            break
        }
    }

    return headerLines
}

val checkLicenseHeaders by tasks.registering {
    val files = getCopyrightableFiles(rootDir)

    inputs.files(files)

    // To be on the safe side in case any called helper functions are not thread safe.
    mustRunAfter(checkCopyrightsInNoticeFile)

    doLast {
        var hasErrors = false

        files.forEach { file ->
            val headerLines = extractLicenseHeader(file)

            val holders = extractCopyrightHolders(headerLines)
            if (holders.singleOrNull() != expectedCopyrightHolder) {
                hasErrors = true
                logger.error("Unexpected copyright holder(s) in file '$file': $holders")
            }

            if (!headerLines.joinToString("\n").endsWith(expectedLicenseHeader)) {
                hasErrors = true
                logger.error("Unexpected license header in file '$file'.")
            }
        }

        if (hasErrors) throw GradleException("There were errors in license headers.")
    }
}

val checkGitAttributes by tasks.registering {
    val gitFilesProvider = providers.of(GitFilesValueSource::class) { parameters { workingDir = rootDir } }

    inputs.files(gitFilesProvider)

    doLast {
        var hasErrors = false

        val files = gitFilesProvider.get()
        val gitAttributesFiles = files.filter { it.endsWith(".gitattributes") }
        val commentChars = setOf('#', '/')

        gitAttributesFiles.forEach { gitAttributesFile ->
            logger.lifecycle("Checking file '$gitAttributesFile'...")

            val ignoreRules = gitAttributesFile.readLines()
                // Skip empty and comment lines.
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.first() !in commentChars }
                // The patterns is the part before the first whitespace.
                .mapTo(mutableSetOf()) { line -> line.takeWhile { !it.isWhitespace() } }
                // Create ignore rules from valid patterns.
                .mapIndexedNotNull { index, pattern ->
                    runCatching {
                        FastIgnoreRule(pattern)
                    }.onFailure {
                        logger.warn("File '$gitAttributesFile' contains an invalid pattern in line ${index + 1}: $it")
                    }.getOrNull()
                }

            // Check only those files that are in scope of this ".gitattributes" file.
            val gitAttributesDir = gitAttributesFile.parentFile
            val filesInScope = files.filter { it.startsWith(gitAttributesDir) }

            ignoreRules.forEach { rule ->
                val matchesAnything = filesInScope.any { file ->
                    val relativeFile = file.relativeTo(gitAttributesDir)
                    rule.isMatch(relativeFile.invariantSeparatorsPath, /* directory = */ false)
                }

                if (!matchesAnything) {
                    hasErrors = true
                    logger.error("Rule '$rule' does not match anything.")
                }
            }
        }

        if (hasErrors) throw GradleException("There were stale '.gitattribute' entries.")
    }
}
