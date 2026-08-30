package io.github.mr1ve3r.combined.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The password-encrypted export container (SPEC 8.4). */
class ProfileContainerTest {

    private val payload = """{"displayName":"Home","password":"hunter2"}"""

    @Test
    fun aSealedContainerOpensWithItsPassword() {
        val sealed = ProfileContainer.seal(payload, "correct horse")

        assertEquals(payload, ProfileContainer.open(sealed, "correct horse"))
    }

    @Test
    fun theSealedTextCarriesNothingReadable() {
        val sealed = ProfileContainer.seal(payload, "correct horse")

        assertFalse(sealed.contains("hunter2"))
        assertFalse(sealed.contains("Home"))
    }

    @Test
    fun theSameExportSealedTwiceDiffers() {
        assertNotEquals(
            ProfileContainer.seal(payload, "correct horse"),
            ProfileContainer.seal(payload, "correct horse"),
        )
    }

    @Test(expected = ProfileContainerException::class)
    fun theWrongPasswordIsRefused() {
        ProfileContainer.open(ProfileContainer.seal(payload, "correct horse"), "correct hors")
    }

    @Test(expected = ProfileContainerException::class)
    fun plainJsonIsNotAContainer() {
        ProfileContainer.open(payload, "correct horse")
    }

    @Test
    fun aContainerIsToldApartFromAPlainExport() {
        assertTrue(ProfileContainer.isContainer(ProfileContainer.seal(payload, "correct horse")))
        assertFalse(ProfileContainer.isContainer(payload))
    }
}
