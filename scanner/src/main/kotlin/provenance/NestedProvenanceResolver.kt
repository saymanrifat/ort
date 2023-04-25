/*
 * Copyright (C) 2021 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.scanner.provenance

import kotlinx.coroutines.runBlocking

import org.apache.logging.log4j.kotlin.Logging

import org.ossreviewtoolkit.model.ArtifactProvenance
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.KnownProvenance
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.SourceCodeOrigin
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.toYaml
import org.ossreviewtoolkit.scanner.utils.DefaultWorkingTreeCache
import org.ossreviewtoolkit.scanner.utils.WorkingTreeCache

/**
 * The [NestedProvenanceResolver] provides a function to resolve nested provenances.
 */
interface NestedProvenanceResolver {
    /**
     * Resolve nested [Provenance]s of the provided [provenance]. For an [ArtifactProvenance] the returned
     * [NestedProvenance] always contains only the provided [ArtifactProvenance]. For a [RepositoryProvenance] the
     * resolver looks for nested repositories, for example Git submodules or Mercurial subrepositories.
     */
    fun resolveNestedProvenance(provenance: KnownProvenance): NestedProvenance
}

/**
 * The default implementation of [NestedProvenanceResolver].
 */
class DefaultNestedProvenanceResolver(
    private val storage: NestedProvenanceStorage,
    private val workingTreeCache: WorkingTreeCache
) : NestedProvenanceResolver {
    private companion object : Logging

    override fun resolveNestedProvenance(provenance: KnownProvenance): NestedProvenance {
        return when (provenance) {
            is ArtifactProvenance -> NestedProvenance(root = provenance, subRepositories = emptyMap())
            is RepositoryProvenance -> runBlocking { resolveNestedRepository(provenance) }
        }
    }

    private suspend fun resolveNestedRepository(provenance: RepositoryProvenance): NestedProvenance {
        val storedResult = storage.readNestedProvenance(provenance)

        if (storedResult != null) {
            if (storedResult.hasOnlyFixedRevisions) {
                logger.info {
                    "Found a stored nested provenance for $provenance with only fixed revisions, skipping resolution."
                }

                return storedResult.nestedProvenance
            } else {
                logger.info {
                    "Found a stored nested provenance for $provenance with at least one non-fixed revision, " +
                            "restarting resolution."
                }
            }
        } else {
            logger.info {
                "Could not find a stored nested provenance for $provenance, attempting resolution."
            }
        }

        return workingTreeCache.use(provenance.vcsInfo) { vcs, workingTree ->
            vcs.updateWorkingTree(
                workingTree,
                provenance.resolvedRevision,
                recursive = true
            ).onFailure { throw it }

            val subRepositories = workingTree.getNested().mapValues { (_, nestedVcs) ->
                // TODO: Verify that the revision is always a resolved revision.
                RepositoryProvenance(nestedVcs, nestedVcs.revision)
            }

            NestedProvenance(root = provenance, subRepositories = subRepositories).also { nestedProvenance ->
                // TODO: Find a way to figure out if the nested repository is configured with a fixed revision to
                //       correctly set `hasOnlyFixedRevisions`. For now always assume that they are fixed because that
                //       should be correct for most cases and otherwise the storage would have no effect.
                storage.putNestedProvenance(
                    provenance,
                    NestedProvenanceResolutionResult(nestedProvenance, hasOnlyFixedRevisions = true)
                )
            }
        }
    }
}


fun main() {
    val pkg = Package.EMPTY.copy(
        id = Identifier("Maven:ort:ort:1.0.0"),
        vcsProcessed = VcsInfo(
            type = VcsType.GIT,
            url = "https://github.com/oss-review-toolkit/ort.git",
            path = "",
            revision = "fviernau/test"
        )
    )

    val workingTreeCache = DefaultWorkingTreeCache()

    val ppr = DefaultPackageProvenanceResolver(DummyProvenanceStorage(), workingTreeCache)
    val npr = DefaultNestedProvenanceResolver(
        DummyNestedProvenanceStorage(),
        workingTreeCache
    )

    val rp = ppr.resolveProvenance(pkg, listOf(SourceCodeOrigin.VCS))
    println(rp.toYaml())
    val np = npr.resolveNestedProvenance(rp)
    println(np.toYaml())
}

internal class DummyNestedProvenanceStorage : NestedProvenanceStorage {
    override fun readNestedProvenance(root: RepositoryProvenance): NestedProvenanceResolutionResult? = null
    override fun putNestedProvenance(root: RepositoryProvenance, result: NestedProvenanceResolutionResult) {
        /** no-op */
    }
}

internal class DummyProvenanceStorage : PackageProvenanceStorage {
    override fun readProvenance(id: Identifier, sourceArtifact: RemoteArtifact): PackageProvenanceResolutionResult? =
        null

    override fun readProvenance(id: Identifier, vcs: VcsInfo): PackageProvenanceResolutionResult? = null

    override fun readProvenances(id: Identifier): List<PackageProvenanceResolutionResult> = emptyList()

    override fun putProvenance(id: Identifier, vcs: VcsInfo, result: PackageProvenanceResolutionResult) { /** no-op */ }

    override fun putProvenance(
        id: Identifier,
        sourceArtifact: RemoteArtifact,
        result: PackageProvenanceResolutionResult
    ) { /** no-op */ }
}
