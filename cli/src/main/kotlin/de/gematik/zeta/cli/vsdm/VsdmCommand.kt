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
package de.gematik.zeta.cli.vsdm

import com.github.ajalt.clikt.core.Context
import de.gematik.zeta.cli.ZetaCliktCommand

/**
 * `zeta vsdm` — Versichertenstammdaten (VSD) operations. A container for the verbs; the read itself
 * lives in [VsdmGetCommand] (`zeta vsdm get`).
 */
class VsdmCommand : ZetaCliktCommand(name = "vsdm") {
    override fun help(context: Context) =
        "Read Versichertenstammdaten (VSD) from a PoPP token."
}
