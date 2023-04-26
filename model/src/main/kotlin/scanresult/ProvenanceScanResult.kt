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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

import java.time.Instant

import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.KnownProvenance
import org.ossreviewtoolkit.model.ScannerDetails
import org.ossreviewtoolkit.utils.spdx.SpdxExpression

data class ProvenanceScanResult(
    /**
     * Provenance information about the scanned source code.
     */
    val provenance: KnownProvenance,

    /**
     * The time when the scan started.
     */
    val startTime: Instant,

    /**
     * The time when the scan finished.
     */
    val endTime: Instant,

    /**
     * Details about the used scanner.
     */
    val scanner: ScannerDetails,

    /**
     * The detected license findings.
     */
    @JsonProperty("licenses")
    val licenseFindings: List<FileFinding<SpdxExpression>>,

    /**
     * The detected copyright findings.
     */
    @JsonProperty("copyrights")
    val copyrightFindings: List<FileFinding<String>>,

    /**
     * A map for scanner specific data that cannot be mapped into any generalized property, but still needs to be
     * stored in the scan result.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val additionalData: Map<String, String> = emptyMap(),

    /**
     * The list of issues that occurred during the scan.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val issues: List<Issue> = emptyList()
)
