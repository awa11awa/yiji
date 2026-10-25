package org.ntqqrev.acidify.common.android

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.ntqqrev.acidify.common.SignResult
import org.ntqqrev.acidify.exception.UrlSignException

class AndroidLegacyUrlSignProvider(
    val url: String,
    val fullVersion: String,
    val fekitVersion: String,
    val androidId: String,
    val qimei36: String,
    val httpProxy: String? = null,
) : AndroidSignProvider {
    private val base = Url(url)

    private val client = HttpClient {
        if (!(base.user.isNullOrEmpty() || base.password.isNullOrEmpty())) {
            install(Auth) {
                basic {
                    credentials {
                        BasicAuthCredentials(
                            username = base.user!!,
                            password = base.password!!
                        )
                    }
                }
            }
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            if (!httpProxy.isNullOrEmpty()) {
                proxy = ProxyBuilder.http(httpProxy)
            }
        }
    }

    override suspend fun sign(
        uin: Long,
        cmd: String,
        buffer: ByteArray,
        guid: String,
        seq: Int,
        version: String,
        qua: String
    ): SignResult = signInternal(
        uin = uin,
        cmd = cmd,
        buffer = buffer,
        guid = guid,
        seq = seq,
        qua = qua,
        allowRegisterRetry = true,
    )

    override suspend fun energy(
        uin: Long,
        data: String,
        guid: String,
        ver: String,
        version: String,
        qua: String
    ): ByteArray = energyInternal(
        uin = uin,
        data = data,
        guid = guid,
        ver = ver,
        version = version,
        allowRegisterRetry = true,
    )

    override suspend fun getDebugXwid(
        uin: Long,
        data: String,
        guid: String,
        version: String,
        qua: String
    ): ByteArray =
        "0A93010A9001413441393532384243323537343030303644314445303543333538313245363041454239333935424444364433323731454644454542394634304536383734313141444439393835353341364436414132413433363637373839463746424236333442443444443031443436353735323238373339393032373836443445384333313241443631444346424537413246"
            .hexToByteArray()

    suspend fun registerDevice(
        uin: Long,
        guid: String,
    ): Boolean = register(uin, guid)

    private suspend fun signInternal(
        uin: Long,
        cmd: String,
        buffer: ByteArray,
        guid: String,
        seq: Int,
        qua: String,
        allowRegisterRetry: Boolean,
    ): SignResult {
        val response = client.post {
            url {
                takeFrom(base)
                appendPathSegments("sign")
            }
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("ver", fullVersion)
                        append("fekit_ver", fekitVersion)
                        append("qua", qua)
                        append("uin", uin.toString())
                        append("cmd", cmd)
                        append("seq", seq.toString())
                        append("android_id", androidId)
                        append("qimei36", qimei36)
                        append("buffer", buffer.toHexString())
                        append("guid", guid)
                    }
                )
            )
        }
        response.ensureSuccess()
        val body = response.body<AndroidLegacyUrlSignResponse<AndroidLegacyUrlSignValue>>()
        if (body.code == 1 && allowRegisterRetry && body.msg.contains("Uin is not registered.")) {
            if (register(uin, guid)) {
                return signInternal(
                    uin = uin,
                    cmd = cmd,
                    buffer = buffer,
                    guid = guid,
                    seq = seq,
                    qua = qua,
                    allowRegisterRetry = false,
                )
            }
        }
        val value = body.data ?: throw UrlSignException(body.msg, body.code)
        if (body.code != 0) {
            throw UrlSignException(body.msg, body.code)
        }
        return SignResult(
            sign = value.sign.hexToByteArray(),
            token = value.token.hexToByteArray(),
            extra = value.extra.hexToByteArray(),
        )
    }

    private suspend fun energyInternal(
        uin: Long,
        data: String,
        guid: String,
        ver: String,
        version: String,
        allowRegisterRetry: Boolean,
    ): ByteArray {
        val response = client.get {
            url {
                takeFrom(base)
                appendPathSegments("energy")
                parameters.append("ver", version)
                parameters.append("fekit_ver", fekitVersion)
                parameters.append("uin", uin.toString())
                parameters.append("data", data)
                parameters.append("android_id", androidId)
                parameters.append("qimei36", qimei36)
                parameters.append("guid", guid)
                parameters.append("version", ver)
            }
        }
        response.ensureSuccess()
        val body = response.body<AndroidLegacyUrlSignResponse<String>>()
        if (body.code == 1 && allowRegisterRetry && body.msg.contains("Uin is not registered.")) {
            if (register(uin, guid)) {
                return energyInternal(
                    uin = uin,
                    data = data,
                    guid = guid,
                    ver = ver,
                    version = version,
                    allowRegisterRetry = false,
                )
            }
        }
        val value = body.data ?: throw UrlSignException(body.msg, body.code)
        if (body.code != 0) {
            throw UrlSignException(body.msg, body.code)
        }
        return value.hexToByteArray()
    }

    private suspend fun register(
        uin: Long,
        guid: String,
    ): Boolean {
        val response = client.get {
            url {
                takeFrom(base)
                appendPathSegments("register")
                parameters.append("ver", fullVersion)
                parameters.append("fekit_ver", fekitVersion)
                parameters.append("uin", uin.toString())
                parameters.append("android_id", androidId)
                parameters.append("qimei36", qimei36)
                parameters.append("guid", guid)
            }
        }
        response.ensureSuccess()
        val body = response.body<AndroidLegacyUrlSignResponse<JsonElement>>()
        return body.code == 0
    }
}

private fun HttpResponse.ensureSuccess() {
    if (status != HttpStatusCode.OK) {
        throw UrlSignException(status.description, status.value)
    }
}

@Serializable
private data class AndroidLegacyUrlSignResponse<T>(
    val code: Int = 0,
    val msg: String = "",
    val data: T? = null,
)

@Serializable
private data class AndroidLegacyUrlSignValue(
    val sign: String,
    val token: String,
    val extra: String
)