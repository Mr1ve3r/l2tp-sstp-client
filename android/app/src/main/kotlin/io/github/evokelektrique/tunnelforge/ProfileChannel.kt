package io.github.evokelektrique.tunnelforge

import io.github.mr1ve3r.combined.core.profile.ProfileContainer
import io.github.mr1ve3r.combined.core.profile.ProfileContainerException
import io.github.mr1ve3r.combined.core.profile.ProfileStore
import io.github.mr1ve3r.combined.core.trust.store.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The profile store as Flutter calls it (SPEC phase 8).
 *
 * Flutter keeps no profiles of its own. It asks for the list to draw it, asks
 * for one profile with its secrets when the user opens the editor, and hands
 * back what the user saved. Everything else — which profile is current, what
 * the service reads when the system starts it — happens on this side.
 *
 * The certificates an SSTP profile trusts are stored by [TrustStore], because
 * that relation already had a table; this class keeps the two writes together
 * so the caller sees one profile.
 */
class ProfileChannel(
    private val profiles: ProfileStore,
    private val trust: TrustStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Answers one call. Success and failure are both reported through [reply]. */
    fun handle(method: String, arguments: Any?, reply: TrustChannel.Reply) {
        val args = arguments as? Map<*, *>
        when (method) {
            ProfileContract.LIST_PROFILES -> answer(reply) { list() }

            ProfileContract.LAST_PROFILE_ID -> reply.success(profiles.lastProfileId())

            ProfileContract.SET_LAST_PROFILE_ID -> {
                profiles.setLastProfileId(args?.get(ProfileContract.ARG_ID) as? String)
                reply.success(null)
            }

            ProfileContract.LOAD_PROFILE -> withId(args, reply) { id -> loaded(id) }

            ProfileContract.DELETE_PROFILE -> withId(args, reply) { id -> profiles.delete(id) }

            ProfileContract.SAVE_PROFILE -> {
                val map = args?.get(ProfileContract.ARG_PROFILE) as? Map<*, *>
                if (map == null) {
                    reply.error(ProfileContract.ERROR_BAD_ARGS, "A profile is required")
                    return
                }
                answer(reply) {
                    save(
                        map = map,
                        password = args[ProfileContract.ARG_PASSWORD] as? String ?: "",
                        psk = args[ProfileContract.ARG_PSK] as? String ?: "",
                        proxyPassword = args[ProfileContract.ARG_PROXY_PASSWORD] as? String ?: "",
                    )
                }
            }

            ProfileContract.IMPORT_LEGACY_PROFILES -> {
                val rows = args?.get(ProfileContract.ARG_PROFILES) as? List<*> ?: emptyList<Any?>()
                answer(reply) { importLegacy(rows) }
            }

            ProfileContract.SEAL_EXPORT -> withPayload(args, reply) { payload, password ->
                ProfileContainer.seal(payload, password)
            }

            ProfileContract.OPEN_EXPORT -> withPayload(args, reply) { payload, password ->
                ProfileContainer.open(payload, password)
            }

            else -> reply.notImplemented()
        }
    }

    private suspend fun list(): List<Map<String, Any?>> = profiles.list().map { profile ->
        ProfilePayloads.write(profile, trust.certificateIdsFor(profile.id))
    }

    private suspend fun loaded(id: String): Map<String, Any?>? {
        val row = profiles.findWithSecrets(id) ?: return null
        return mapOf(
            ProfileContract.ARG_PROFILE to ProfilePayloads.write(row.profile, trust.certificateIdsFor(id)),
            ProfileContract.ARG_PASSWORD to row.password,
            ProfileContract.ARG_PSK to row.psk,
            ProfileContract.ARG_PROXY_PASSWORD to row.proxyPassword,
        )
    }

    private suspend fun save(
        map: Map<*, *>,
        password: String,
        psk: String,
        proxyPassword: String,
    ): Map<String, Any?> {
        val id = (map[ProfileContract.FIELD_ID] as? String)?.takeIf { it.isNotEmpty() } ?: ProfileStore.newProfileId()
        val profile =
            ProfilePayloads.read(map, id, clock())
                ?: throw ProfileChannelException(ProfileContract.ERROR_BAD_ARGS, "A profile needs a server")
        val stored = profiles.save(profile, password = password, psk = psk, proxyPassword = proxyPassword)
        trust.setCertificatesFor(id, ProfilePayloads.trustedCertificateIds(map))
        return ProfilePayloads.write(stored, trust.certificateIdsFor(id))
    }

    /**
     * Takes over the profiles Flutter used to own (SPEC 8.1.3).
     *
     * Runs once. A second call is answered `false` and changes nothing: the
     * profiles Flutter still has in its old preferences are a snapshot from
     * before the move, and replaying them would undo every edit made since.
     *
     * Creation times are spaced one millisecond apart in the order the rows
     * arrive, so the list keeps the order the user arranged it in.
     *
     * @return whether the import ran.
     */
    private suspend fun importLegacy(rows: List<*>): Boolean {
        if (profiles.legacyImportDone()) return false
        val base = clock() - rows.size
        rows.forEachIndexed { index, entry ->
            val row = entry as? Map<*, *> ?: return@forEachIndexed
            val map = row[ProfileContract.ARG_PROFILE] as? Map<*, *> ?: return@forEachIndexed
            val id = (map[ProfileContract.FIELD_ID] as? String)?.takeIf { it.isNotEmpty() } ?: ProfileStore.newProfileId()
            val profile = ProfilePayloads.read(map, id, base + index) ?: return@forEachIndexed
            profiles.save(
                profile.copy(createdAt = base + index),
                password = row[ProfileContract.ARG_PASSWORD] as? String ?: "",
                psk = row[ProfileContract.ARG_PSK] as? String ?: "",
                proxyPassword = "",
            )
        }
        profiles.markLegacyImportDone()
        AppLog.i(TAG, "legacy_profile_import count=${rows.size}")
        return true
    }

    private fun withId(args: Map<*, *>?, reply: TrustChannel.Reply, action: suspend (String) -> Any?) {
        val id = args?.get(ProfileContract.ARG_ID) as? String
        if (id.isNullOrEmpty()) {
            reply.error(ProfileContract.ERROR_BAD_ARGS, "A profile id is required")
            return
        }
        answer(reply) { action(id) }
    }

    private fun withPayload(
        args: Map<*, *>?,
        reply: TrustChannel.Reply,
        action: (String, String) -> String,
    ) {
        val payload = args?.get(ProfileContract.ARG_PAYLOAD) as? String
        val password = args?.get(ProfileContract.ARG_PASSWORD) as? String
        if (payload.isNullOrEmpty() || password.isNullOrEmpty()) {
            reply.error(ProfileContract.ERROR_BAD_ARGS, "A payload and a password are required")
            return
        }
        try {
            reply.success(action(payload, password))
        } catch (e: ProfileContainerException) {
            reply.error(ProfileContract.ERROR_BAD_PASSWORD, e.message.orEmpty())
        }
    }

    /**
     * Runs [work] and replies with what it returned.
     *
     * The store touches a database, so it cannot run on the calling thread;
     * [scope] is expected to dispatch on the main thread, which is where a
     * method channel reply has to be made.
     */
    private fun answer(reply: TrustChannel.Reply, work: suspend () -> Any?) {
        scope.launch {
            try {
                reply.success(work())
            } catch (e: ProfileChannelException) {
                reply.error(e.code, e.message.orEmpty())
            } catch (e: Exception) {
                AppLog.e(TAG, "profile_call failed", e)
                reply.error(ProfileContract.ERROR_STORE_FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private companion object {
        const val TAG = "ProfileChannel"
    }
}

/** A failure with a code the UI can act on, rather than a stack trace. */
class ProfileChannelException(val code: String, message: String) : Exception(message)
