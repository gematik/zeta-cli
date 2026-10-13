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
