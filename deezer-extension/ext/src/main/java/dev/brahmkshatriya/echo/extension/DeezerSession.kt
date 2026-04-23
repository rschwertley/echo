package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.settings.Settings
import java.util.concurrent.atomic.AtomicReference

class DeezerSession(
    var settings: Settings? = null,
    @Volatile var arlExpired: Boolean = false
) {

    data class DeezerCredentials(
        val arl: String,
        val sid: String,
        val token: String,
        val userId: String,
        val licenseToken: String,
        val email: String,
        val pass: String
    )

    private val credentialsRef = AtomicReference(
        DeezerCredentials("", "", "", "", "", "", "")
    )

    var credentials: DeezerCredentials
        get() = credentialsRef.get()
        private set(value) = credentialsRef.set(value)

    fun updateCredentials(
        arl: String? = null,
        sid: String? = null,
        token: String? = null,
        userId: String? = null,
        licenseToken: String? = null,
        email: String? = null,
        pass: String? = null
    ) {
        credentialsRef.updateAndGet { current ->
            current.copy(
                arl = arl ?: current.arl,
                sid = sid ?: current.sid,
                token = token ?: current.token,
                userId = userId ?: current.userId,
                licenseToken = licenseToken ?: current.licenseToken,
                email = email ?: current.email,
                pass = pass ?: current.pass
            )
        }
    }

    fun isArlExpired(expired: Boolean) {
        arlExpired = expired
    }

    companion object {
        @Volatile
        private var instance: DeezerSession? = null

        fun getInstance(): DeezerSession {
            return instance ?: synchronized(this) {
                instance ?: DeezerSession().also { instance = it }
            }
        }
    }
}