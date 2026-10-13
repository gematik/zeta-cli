/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
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
