package de.pyryco.mobile.recording

import org.junit.Assert.assertThrows
import org.junit.Test

class SuiteSafetyTest {
    @Test fun opaqueArtifactMembersCannotHideKnownCredentialsOrCredentialPrefixes() {
        val privateValue = "controlled-private-routing-value"
        assertThrows(IllegalStateException::class.java) {
            requireSafeCapturedBytes("log $privateValue".toByteArray(), listOf(privateValue))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireSafeCapturedBytes("log sk-ant-controlled-test-value".toByteArray(), emptyList())
        }
        requireSafeCapturedBytes("controlled diagnostic marker".toByteArray(), listOf(privateValue))
    }
}
