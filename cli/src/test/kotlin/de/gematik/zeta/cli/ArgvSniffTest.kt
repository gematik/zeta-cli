package de.gematik.zeta.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArgvSniffTest {

    @Test
    fun `recognises -f VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("-f", "x.yaml"), "-f", "--file"))
    }

    @Test
    fun `recognises --file VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("--file", "x.yaml"), "-f", "--file"))
    }

    @Test
    fun `recognises --file=VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("--file=x.yaml"), "-f", "--file"))
    }

    @Test
    fun `recognises -f=VALUE`() {
        assertEquals("x.yaml", sniffOptValue(arrayOf("-f=x.yaml"), "-f", "--file"))
    }

    @Test
    fun `trailing -f without value returns null`() {
        assertNull(sniffOptValue(arrayOf("-f"), "-f", "--file"))
    }

    @Test
    fun `empty argv returns null`() {
        assertNull(sniffOptValue(arrayOf(), "-f", "--file"))
    }

    @Test
    fun `first match wins on duplicates`() {
        assertEquals(
            "x.yaml",
            sniffOptValue(arrayOf("-f", "x.yaml", "--file", "y.yaml"), "-f", "--file"),
        )
    }

    @Test
    fun `ignores unrelated args around the match`() {
        assertEquals(
            "x.yaml",
            sniffOptValue(arrayOf("status", "-vv", "--file", "x.yaml", "URL"), "-f", "--file"),
        )
    }

    @Test
    fun `does not match an option whose name is a prefix of an arg`() {
        // "--filey" is not "--file" — must not be consumed.
        assertNull(sniffOptValue(arrayOf("--filey", "x.yaml"), "-f", "--file"))
    }
}
