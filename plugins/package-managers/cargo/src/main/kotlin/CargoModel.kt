package org.ossreviewtoolkit.plugins.packagemanagers.cargo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CargoLockFile(
    val version: Int,

    @SerialName("package")
    val packages: List<CargoPackage>
)

@Serializable
data class CargoPackage(
    val name: String,
    val version: String,
    val source: String?,
    val checksum: String?,
    val dependencies: Set<String> = emptySet()
)
