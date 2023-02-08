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

package org.ossreviewtoolkit.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude

import org.apache.logging.log4j.kotlin.Logging

import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.LicenseFindingCuration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.config.Resolutions
import org.ossreviewtoolkit.model.config.orEmpty
import org.ossreviewtoolkit.utils.common.zipWithCollections
import org.ossreviewtoolkit.utils.ort.createOrtTempFile
import org.ossreviewtoolkit.utils.spdx.model.SpdxLicenseChoice
import java.io.File

/**
 * The common output format for the analyzer and scanner. It contains information about the scanned repository, and the
 * analyzer and scanner will add their result to it.
 */
@Suppress("TooManyFunctions")
data class LegacyOrtResult(
    /**
     * Information about the repository that was used as input.
     */
    val repository: Repository,

    /**
     * An [AnalyzerRun] containing details about the analyzer that was run using [repository] as input. Can be null
     * if the [repository] was not yet analyzed.
     */
    val analyzer: AnalyzerRun? = null,

    /**
     * A [ScannerRun] containing details about the scanner that was run using the result from [analyzer] as input.
     * Can be null if no scanner was run.
     */
    val scanner: ScannerRun? = null,

    /**
     * An [AdvisorRun] containing details about the advisor that was run using the result from [analyzer] as input.
     * Can be null if no advisor was run.
     */
    val advisor: AdvisorRun? = null,

    /**
     * An [EvaluatorRun] containing details about the evaluation that was run using the result from [scanner] as
     * input. Can be null if no evaluation was run.
     */
    val evaluator: EvaluatorRun? = null,

    /**
     * A [ResolvedConfiguration] containing data resolved during the analysis which augments the automatically
     * determined data.
     */
    val resolvedConfiguration: LegacyResolvedConfiguration = LegacyResolvedConfiguration(),

    /**
     * User defined labels associated to this result. Labels are not used by ORT itself, but can be used in parts of ORT
     * which are customizable by the user, for example in evaluator rules or in the notice reporter.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val labels: Map<String, String> = emptyMap()
) {
    companion object : Logging {
        /**
         * A constant for an [OrtResult] with an empty repository and all other properties `null`.
         */
        @JvmField
        val EMPTY = OrtResult(
            repository = Repository.EMPTY,
            analyzer = null,
            scanner = null,
            advisor = null,
            evaluator = null,
            labels = emptyMap()
        )
    }



    private data class ProjectEntry(val project: Project, val isExcluded: Boolean)



    private data class PackageEntry(
        val pkg: Package?,
        val curatedPackage: CuratedPackage?,
        val isExcluded: Boolean
    )


    private val scanResultsById: Map<Identifier, List<ScanResult>> by lazy { scanner?.scanResults.orEmpty() }

    private val advisorResultsById: Map<Identifier, List<AdvisorResult>> by lazy {
        advisor?.results?.advisorResults.orEmpty()
    }



    /**
     * Return a map of all de-duplicated [Issue]s associated by [Identifier].
     */
    fun collectIssues(): Map<Identifier, Set<Issue>> {
        val analyzerIssues = analyzer?.result?.collectIssues().orEmpty()
        val scannerIssues = scanner?.collectIssues().orEmpty()
        val advisorIssues = advisor?.results?.collectIssues().orEmpty()

        val analyzerAndScannerIssues = analyzerIssues.zipWithCollections(scannerIssues)
        return analyzerAndScannerIssues.zipWithCollections(advisorIssues)
    }





    /**
     * Return all [SpdxLicenseChoice]s for the [Package] with [id].
     */
    fun getPackageLicenseChoices(id: Identifier): List<SpdxLicenseChoice> =
        repository.config.licenseChoices.packageLicenseChoices.find { it.packageId == id }?.licenseChoices.orEmpty()

    /**
     * Return all [SpdxLicenseChoice]s applicable for the scope of the whole [repository].
     */
    @JsonIgnore
    fun getRepositoryLicenseChoices(): List<SpdxLicenseChoice> =
        repository.config.licenseChoices.repositoryLicenseChoices

    /**
     * Return the list of [AdvisorResult]s for the given [id].
     */
    fun getAdvisorResultsForId(id: Identifier): List<AdvisorResult> = advisorResultsById[id].orEmpty()

    /**
     * Return all [RuleViolation]s contained in this [OrtResult]. Optionally exclude resolved violations with
     * [omitResolved] and remove violations below the [minSeverity].
     */
    @JsonIgnore
    fun getRuleViolations(omitResolved: Boolean = false, minSeverity: Severity? = null): List<RuleViolation> {
        val allViolations = evaluator?.violations.orEmpty()

        val severeViolations = when (minSeverity) {
            null -> allViolations
            else -> allViolations.filter { it.severity >= minSeverity }
        }

        return if (omitResolved) {
            val resolutions = getResolutions().ruleViolations

            severeViolations.filter { violation ->
                resolutions.none { resolution ->
                    resolution.matches(violation)
                }
            }
        } else {
            severeViolations
        }
    }


    @JsonIgnore
    fun getExcludes(): Excludes = repository.config.excludes




    /**
     * Return the [Resolutions] contained in the repository configuration of this [OrtResult].
     */
    @JsonIgnore
    fun getResolutions(): Resolutions = repository.config.resolutions.orEmpty()

    /**
     * Return the list of [ScanResult]s for the given [id].
     */
    fun getScanResultsForId(id: Identifier): List<ScanResult> = scanResultsById[id].orEmpty()


    /**
     * Return the label values corresponding to the given [key] split at the delimiter ',', or an empty set if the label
     * is absent.
     */
    fun getLabelValues(key: String): Set<String> =
        labels[key]?.split(',').orEmpty().mapTo(mutableSetOf()) { it.trim() }

    /**
     * Return true if a [label] with [value] exists in this [OrtResult]. If [value] is null the value of the label is
     * ignored. If [splitValue] is true, the label value is interpreted as comma-separated list.
     */
    fun hasLabel(label: String, value: String? = null, splitValue: Boolean = true) =
        if (value == null) {
            label in labels
        } else if (splitValue) {
            value in getLabelValues(label)
        } else {
            labels[label] == value
        }
}

/**
 * Return a set containing exactly one [CuratedPackage] for each given [Package], derived from applying all
 * given [curations] to the packages they apply to. The given [curations] must be ordered highest-priority-first, which
 * is the inverse order of their application.
 */
private fun applyPackageCurations(
    packages: Collection<Package>,
    curations: List<PackageCuration>
): Set<CuratedPackage> {
    val curationsForId = packages.associateBy(
        keySelector = { pkg -> pkg.id },
        valueTransform = { pkg ->
            curations.filter { curation ->
                curation.isApplicable(pkg.id)
            }
        }
    )

    return packages.mapTo(mutableSetOf()) { pkg ->
        curationsForId[pkg.id].orEmpty().asReversed().fold(pkg.toCuratedPackage()) { cur, packageCuration ->
            OrtResult.logger.debug {
                "Applying curation '$packageCuration' to package '${pkg.id.toCoordinates()}'."
            }

            packageCuration.apply(cur)
        }
    }
}

fun main() {
    val ortRepoDir = File("/home/frank/devel/ort/ort")
    val extensions = setOf("json", "yml", "yaml")
    val candidateFiles = ortRepoDir.walkBottomUp().filter { it.isFile && it.extension in extensions }.toList().filter {
        it.absolutePath.contains("/src/funTest/assets/") || it.absolutePath.contains("/src/test/assets/")
    }

    println("candidateFiles: " + candidateFiles.size)
    candidateFiles.forEach {
        patch(it)
    }
}

//    "<REPLACE_URL>" to "http://anchdms.asdah.com/dasd/dasda/afdh",
//    "<REPLACE_PATH>" to "dsajkdsauvioxc",
//    "<REPLACE_DEFINITION_FILE_PATH>", "bnmnasd/sdajkl",
//    "<REPLACE_URL_PROCESSED>", "http://dasda.asdah.com/dasd/dasda/afdh.git"
// "<REPLACE_REVISION>" to "231298743216",

private val PLACE_HOLDERS = listOf(
    "\"<REPLACE_PROCESSORS>\"" to "31231542",
    "\"<REPLACE_MAX_MEMORY>\"" to "784932"

)
fun String.replacePlaceHolders(): String {
    var result = this

    PLACE_HOLDERS.forEach {
        result = result.replace(it.first, it.second)
    }

    return result
}

fun String.restorePlaceHolders(): String {
    var result = this

    PLACE_HOLDERS.forEach {
        result = result.replace(it.second, it.first)
    }

    return result
}

fun patch(inputFile: File) {
    try {
        val tempFile = createOrtTempFile("ort-patch", ".${inputFile.extension}")

        tempFile.writeText(inputFile.readText().replacePlaceHolders())

        val legacyOrtResult = tempFile.readValue<LegacyOrtResult>()
        val ortResult = legacyOrtResult.convert()
        tempFile.writeValue(ortResult)

        inputFile.writeText(tempFile.readText().restorePlaceHolders())
        println("patched: ${inputFile.absolutePath}")
    } catch (e: Exception) {

    }
}



fun LegacyOrtResult.convert(): OrtResult {
    return OrtResult(
        analyzer = analyzer,
        advisor = advisor,
        evaluator = evaluator,
        labels = labels,
        repository = repository,
        scanner = scanner,
        resolvedConfiguration = resolvedConfiguration.convert()
    )
}

fun LegacyResolvedConfiguration.convert(): ResolvedConfiguration {
    return ResolvedConfiguration(
        packageCurations = PackageCurations(
            providers = listOf("DefaultDir"),
            data = mapOf("DefaultDir" to packageCurations)
        )
    )
}
