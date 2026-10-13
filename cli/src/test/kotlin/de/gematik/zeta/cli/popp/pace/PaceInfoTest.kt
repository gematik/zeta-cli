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
package de.gematik.zeta.cli.popp.pace

import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaceInfoTest {
    private val hex = HexFormat.of()

    /** An eGK's EF.CardAccess: PACE-ECDH-GM-AES-CBC-CMAC-128, version 2, brainpoolP256r1. */
    private val cardAccess = hex.parseHex("31143012060A04007F0007020204020202010202010D")

    @Test
    fun `reads the protocol and its bare OID octets`() {
        val paceInfo = PaceInfo(cardAccess)
        assertEquals("0.4.0.127.0.7.2.2.4.2.2", paceInfo.protocolId)
        assertArrayEquals(hex.parseHex("04007F00070202040202"), paceInfo.protocolBytes)
    }

    @Test
    fun `maps the standardized domain parameter id to its curve`() {
        assertEquals("BrainpoolP256r1", PaceInfo(cardAccess).curveName)
    }

    @Test
    fun `skips SecurityInfos that are not PACEInfo`() {
        // A CardInfoLocator (0.4.0.127.0.7.2.2.6) ahead of the PACEInfo.
        val withOtherInfo = hex.parseHex(
            "3120300A060804007F00070202063012060A04007F0007020204020202010202010D",
        )
        assertEquals("0.4.0.127.0.7.2.2.4.2.2", PaceInfo(withOtherInfo).protocolId)
    }

    @Test
    fun `rejects a card offering no PACE parameters`() {
        val noPaceInfo = hex.parseHex("310C300A060804007F0007020206")
        assertThrows(IllegalStateException::class.java) { PaceInfo(noPaceInfo) }
    }

    @Test
    fun `rejects an unsupported curve`() {
        // Same PACEInfo, but parameter id 12 (NIST P-256), which gemSpec_COS does not use.
        val nistCurve = hex.parseHex("31143012060A04007F0007020204020202010202010C")
        assertThrows(IllegalStateException::class.java) { PaceInfo(nistCurve) }
    }
}
