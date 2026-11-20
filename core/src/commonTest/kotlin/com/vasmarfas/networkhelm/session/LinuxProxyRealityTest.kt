package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinuxProxyRealityTest {

    // the keys of Alice and Bob of section 6.1 of RFC 7748 in base64url, and two keys whose bits the clamping changes
    @Test
    fun thePublicKeyOfRealityIsX25519OfRfc7748() {
        assertEquals("hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo", realityPublicKey("dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo"))
        assertEquals("3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08", realityPublicKey("XasIfmJKikt54X-Lg4AO5m87sSkmGLb9HC-LJ_-I4Os"))
        assertEquals("L-V9o0fNYkMVKNqsX7spBzD_9oSvxM_C7ZCZX1jLO3Q", realityPublicKey("A".repeat(43)))
        assertEquals("hHwNLDdSNPNl5mCVUYejc1oPdhPRYJ06ak2MU66qWiI", realityPublicKey("_".repeat(42) + "8"))
    }

    @Test
    fun aKeyThatIsNo32BytesOfBase64urlIsRefusedAndNotRepeated() {
        for (key in listOf("", "dwdtCnMYpX08", "rd-test+key/in/the/standard/alphabet=")) {
            assertEquals("the private key of Reality is no 32 bytes of base64url", assertFailsWith<DeviceException> { realityPublicKey(key) }.message)
        }
    }
}
