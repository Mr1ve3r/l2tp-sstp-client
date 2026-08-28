package io.github.evokelektrique.tunnelforge

import android.content.Context
import android.net.Uri
import io.github.mr1ve3r.combined.core.trust.CertificateParseException
import io.github.mr1ve3r.combined.core.trust.CertificateParser
import io.github.mr1ve3r.combined.core.trust.ImportCandidate
import io.github.mr1ve3r.combined.core.trust.ServerChainFetcher
import io.github.mr1ve3r.combined.core.trust.store.TrustStore
import io.github.mr1ve3r.combined.engine.TrustPolicy
import java.io.IOException
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The certificate store as Flutter calls it (SPEC 5.3, 5.9).
 *
 * Everything the user does to the store passes through here: the three import
 * paths, the list, deletion, export. The certificates themselves stay on this
 * side — Flutter receives fields and identifiers, and PEM only when a
 * certificate is being offered for import or deliberately exported.
 *
 * @param picker opens the system document picker. That needs an activity,
 *   which this class does not have, so [MainActivity] supplies it.
 * @param allowInsecurePolicy whether this build offers [TrustPolicy.INSECURE].
 *   False in a release build (SPEC 5.5).
 */
class TrustChannel(
    private val context: Context,
    private val store: TrustStore,
    private val scope: CoroutineScope,
    private val picker: DocumentPicker,
    private val allowInsecurePolicy: Boolean,
    private val fetcher: ServerChainFetcher = ServerChainFetcher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Opens the system document picker and hands back what the user chose. */
    fun interface DocumentPicker {
        /** @param onResult called with the chosen document, or `null` if the user backed out. */
        fun pick(onResult: (Uri?) -> Unit)
    }

    /** Answers one call. Success and failure are both reported through [reply]. */
    fun handle(method: String, arguments: Any?, reply: Reply) {
        val args = arguments as? Map<*, *>
        when (method) {
            TrustContract.LIST_CERTIFICATES -> answer(reply) { store.list().map(TrustPayloads::stored) }

            TrustContract.LIST_TRUST_POLICIES -> reply.success(availablePolicies())

            TrustContract.PICK_CERTIFICATE_FILE -> pickAndParse(reply)

            TrustContract.PARSE_PEM_TEXT -> {
                val text = args?.get(TrustContract.ARG_TEXT) as? String
                if (text.isNullOrBlank()) {
                    reply.error(TrustContract.ERROR_BAD_ARGS, "No text to read certificates from")
                    return
                }
                answer(reply) { candidatesOf(runCatching { CertificateParser.parsePem(text) }) }
            }

            TrustContract.FETCH_SERVER_CHAIN -> {
                val host = (args?.get(TrustContract.ARG_HOST) as? String)?.trim()
                val port = (args?.get(TrustContract.ARG_PORT) as? Number)?.toInt()
                if (host.isNullOrBlank() || port == null || port !in 1..MAX_PORT) {
                    reply.error(TrustContract.ERROR_BAD_ARGS, "A host and a port between 1 and $MAX_PORT are required")
                    return
                }
                answer(reply) { fetchChain(host, port) }
            }

            TrustContract.IMPORT_CERTIFICATES -> {
                val requested = args?.get(TrustContract.ARG_CERTIFICATES) as? List<*>
                if (requested.isNullOrEmpty()) {
                    reply.error(TrustContract.ERROR_BAD_ARGS, "No certificates to import")
                    return
                }
                answer(reply) { importAll(requested) }
            }

            TrustContract.DELETE_CERTIFICATE -> withId(args, reply) { id -> store.delete(id) }

            TrustContract.EXPORT_CERTIFICATE -> withId(args, reply) { id -> store.exportPem(id) }

            TrustContract.RENAME_CERTIFICATE -> {
                val id = args?.get(TrustContract.ARG_ID) as? String
                val alias = args?.get(TrustContract.ARG_ALIAS) as? String
                if (id.isNullOrBlank() || alias == null) {
                    reply.error(TrustContract.ERROR_BAD_ARGS, "A certificate id and an alias are required")
                    return
                }
                answer(reply) { store.rename(id, alias) }
            }

            else -> reply.notImplemented()
        }
    }

    /**
     * Which policies this build offers.
     *
     * `INSECURE` is absent from a release build rather than shown disabled, so
     * there is nothing for the user to talk themselves into (SPEC 5.5).
     */
    private fun availablePolicies(): List<String> = TrustPolicy.entries
        .filter { it != TrustPolicy.INSECURE || allowInsecurePolicy }
        .map(TrustPolicy::name)

    private fun pickAndParse(reply: Reply) {
        picker.pick { uri ->
            if (uri == null) {
                reply.success(null)
                return@pick
            }
            answer(reply) {
                val bytes = withContext(Dispatchers.IO) { readDocument(uri) }
                candidatesOf(runCatching { CertificateParser.parse(bytes) })
            }
        }
    }

    private fun readDocument(uri: Uri): ByteArray {
        // The same rule as profile import: a content URI from the picker and
        // nothing else. A file URI would let a caller point this at a path the
        // application can read but the user never chose.
        ProfileImportUriValidator.requireSafeProfileImportUri(uri)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("The selected document could not be opened")
    }

    private suspend fun fetchChain(host: String, port: Int): List<Map<String, Any?>> {
        val chain =
            try {
                fetcher.fetch(host, port)
            } catch (e: IOException) {
                throw TrustChannelException(
                    TrustContract.ERROR_FETCH_FAILED,
                    e.message ?: "Could not read a certificate from $host:$port",
                )
            }
        return describe(chain, withChainPositions = true)
    }

    private suspend fun importAll(requested: List<*>): List<Map<String, Any?>> {
        val now = clock()
        return requested.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val pem = map[TrustContract.ARG_PEM] as? String ?: return@mapNotNull null
            val alias = map[TrustContract.ARG_ALIAS] as? String ?: ""
            val certificate =
                try {
                    CertificateParser.parsePem(pem).first()
                } catch (e: CertificateParseException) {
                    throw TrustChannelException(TrustContract.ERROR_PARSE_FAILED, e.message.orEmpty())
                }
            try {
                TrustPayloads.stored(store.import(certificate, alias, now))
            } catch (e: IOException) {
                throw TrustChannelException(
                    TrustContract.ERROR_STORE_FAILED,
                    e.message ?: "The certificate could not be written to the store",
                )
            }
        }
    }

    private suspend fun candidatesOf(parsed: Result<List<X509Certificate>>): List<Map<String, Any?>> {
        val certificates =
            parsed.getOrElse { error ->
                throw when (error) {
                    is CertificateParseException ->
                        TrustChannelException(TrustContract.ERROR_PARSE_FAILED, error.message.orEmpty())

                    is IOException ->
                        TrustChannelException(TrustContract.ERROR_READ_FAILED, error.message.orEmpty())

                    else -> error
                }
            }
        return describe(certificates, withChainPositions = false)
    }

    private suspend fun describe(
        certificates: List<X509Certificate>,
        withChainPositions: Boolean,
    ): List<Map<String, Any?>> {
        val alreadyImported = store.list().map { it.id }.toSet()
        return ImportCandidate
            .of(certificates, clock(), alreadyImported, withChainPositions)
            .map(TrustPayloads::candidate)
    }

    private fun withId(args: Map<*, *>?, reply: Reply, action: suspend (String) -> Any?) {
        val id = args?.get(TrustContract.ARG_ID) as? String
        if (id.isNullOrBlank()) {
            reply.error(TrustContract.ERROR_BAD_ARGS, "A certificate id is required")
            return
        }
        answer(reply) { action(id) }
    }

    /**
     * Runs [work] and replies with what it returned.
     *
     * Store work touches a database and the filesystem, so it cannot run on the
     * calling thread; [scope] is expected to dispatch on the main thread, which
     * is where a method channel reply has to be made.
     */
    private fun answer(reply: Reply, work: suspend () -> Any?) {
        scope.launch {
            try {
                reply.success(work())
            } catch (e: TrustChannelException) {
                AppLog.w(TAG, "trust_call failed code=${e.code} message=${e.message}")
                reply.error(e.code, e.message.orEmpty())
            } catch (e: Exception) {
                AppLog.e(TAG, "trust_call failed", e)
                reply.error(TrustContract.ERROR_STORE_FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Where an answer goes. Narrower than `MethodChannel.Result`, so this class can be tested. */
    interface Reply {
        fun success(value: Any?)

        fun error(code: String, message: String)

        fun notImplemented()
    }

    private companion object {
        const val TAG = "TrustChannel"
        const val MAX_PORT = 65535
    }
}

/** A failure with a code the UI can act on, rather than a stack trace. */
class TrustChannelException(val code: String, message: String) : Exception(message)
