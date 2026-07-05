package de.gematik.zeta.stress.scenario

/** Which load scenario each attempt runs. */
enum class Scenario { LOGIN_STORM, LOGIN_AND_VSDM_STORM, REFRESH_CHURN }

/**
 * The authenticated resource read performed after login in `login-and-vsdm-storm`. It's issued
 * through the client's SDK HTTP client, so the SDK drives the whole cold chain (token exchange +
 * ASL handshake) on this one request. A cached PoPP token is attached as the `PoPP` header when
 * [popp] is set. Success is any status in [expectStatus].
 */
data class VsdmRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val popp: Boolean = true,
    val expectStatus: Set<Int> = setOf(200, 304),
    val opLabel: String = "vsdm",
)

/**
 * The client population: [institutions] SMC-B identities (Telematik-IDs), each running a random
 * [clientsPerInstitution] OAuth clients (workstations / PVS). `preflight` registers it; `run` drives
 * the whole of it (all clients expire and re-login). [seed] makes the fuzzy fan-out reproducible.
 */
data class CohortSpec(
    val institutions: Int,
    val clientsPerInstitution: IntRange,
    val seed: Long? = null,
)

/** A breaking-point ramp: step the admission rate up until failure/latency thresholds trip. */
data class RampSpec(
    val startPerMin: Int,
    val stepPerMin: Int,
    val stepEverySec: Long,
    val maxPerMin: Int,
    val maxFailPct: Int,
    val maxP99Ms: Long,
    val durationMs: Long?,
)
