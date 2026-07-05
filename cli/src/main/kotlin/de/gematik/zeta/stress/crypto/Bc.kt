package de.gematik.zeta.stress.crypto

import java.security.Provider
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Shared BouncyCastle provider instance. gematik SMC-B keys use brainpool curves the JDK's
 * built-in SunEC provider rejects, so all EC key parsing / signing in the harness goes through BC.
 * Registered globally as well so any transitive SDK code that looks the provider up by name finds it.
 */
val BC_PROVIDER: Provider = BouncyCastleProvider().also {
    if (Security.getProvider(it.name) == null) Security.addProvider(it)
}
