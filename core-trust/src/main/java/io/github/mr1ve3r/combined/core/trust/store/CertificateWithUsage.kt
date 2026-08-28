package io.github.mr1ve3r.combined.core.trust.store

import androidx.room.Embedded

/**
 * A stored certificate together with how many profiles refer to it.
 *
 * The count is what the list screen shows, and what makes deleting a
 * certificate a decision rather than a click (SPEC 5.9).
 */
data class CertificateWithUsage(
    @Embedded val certificate: ServerCertificateEntity,
    val usageCount: Int,
)
