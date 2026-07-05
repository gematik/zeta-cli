package de.gematik.zeta.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One deployed service instance from the catalog's `service_instances` map. */
@Serializable
data class ServiceInstance(val type: String, val url: String)

/**
 * The TI service-discovery catalog. An insurer (IKNR) is routed to a named service instance via
 * `routing[<service>][<iknr>]`, and that name resolves to a base URL in `service_instances`.
 */
@Serializable
data class ServiceCatalog(
    val env: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @SerialName("service_instances") val serviceInstances: Map<String, ServiceInstance> = emptyMap(),
    val routing: Map<String, Map<String, String>> = emptyMap(),
) {
    /** The VSDM base URL serving [insurerId] (IKNR), or null if the IKNR isn't routed / instance missing. */
    fun vsdmBaseUrl(insurerId: String): String? {
        val instance = routing["vsdm"]?.get(insurerId) ?: return null
        return serviceInstances[instance]?.url
    }
}
