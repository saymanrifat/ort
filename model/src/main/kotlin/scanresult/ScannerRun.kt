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

package org.ossreviewtoolkit.model.scanresult

import java.time.Instant

import org.ossreviewtoolkit.model.AccessStatistics
import org.ossreviewtoolkit.model.KnownProvenance
import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.config.ScannerConfiguration
import org.ossreviewtoolkit.utils.ort.Environment

data class ScannerRun(
    /**
     * The [Instant] the scanner was started.
     */
    val startTime: Instant,

    /**
     * The [Instant] the scanner has finished.
     */
    val endTime: Instant,

    /**
     * The [Environment] in which the scanner was executed.
     */
    val environment: Environment,

    /**
     * The [ScannerConfiguration] used for this run.
     */
    val config: ScannerConfiguration,

    /**
     * The [AccessStatistics] for the scan results storage.
     */
    val storageStats: AccessStatistics,

    /**
     * The resolved provenances for all projects and packages.
     */
    val provenances: List<PackageProvenance>,

    /**
     * The direct sub-repositories corresponding to the resolved provenances.
     */
    val nestedProvenances: List<NestedProvenance>,

    /**
     * The list of files for each resolved provenance.
     */
    val provenanceFiles: List<FileListing>,

    /**
     * The scan results for each resolved provenance.
     */
    val provenanceScanResults: List<ProvenanceScanResult>
)

internal fun KnownProvenance.requireEmptyVcsPath() {
    if (this is RepositoryProvenance) {
        require(vcsInfo.path.isEmpty()) {
            "The VCS path must be empty."
        }
    }
}
