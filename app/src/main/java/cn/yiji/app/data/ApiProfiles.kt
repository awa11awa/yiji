package cn.yiji.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ApiProfile(
    val id: String = newId(),
    val name: String = "自定义服务",
    val baseUrl: String = GLM_URL,
    val model: String = GLM_MODEL,
) {
    fun validate() {
        require(name.isNotBlank() && name.length <= 50) { "配置名称应为 1～50 个字" }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "请填写不含密钥、查询参数的 HTTPS API 地址" }
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请填写有效的模型名称" }
    }
    fun endpoint(resource: String): String {
        validate()
        val root = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models")
        return root + "/" + resource
    }
    companion object {
        const val GLM_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val GLM_MODEL = "glm-4.7-flash"
        val glm get() = ApiProfile("builtin-glm", "智谱 · GLM-4.7-Flash", GLM_URL, GLM_MODEL)
        val silicon get() = ApiProfile(name = "硅基流动", baseUrl = "https://api.siliconflow.cn/v1", model = "Qwen/Qwen3.5-4B")
    }
}

interface ApiSecrets {
    fun read(id: String): String
    fun write(id: String, value: String)
    fun remove(id: String)
}

/** Only the wrapping key lives in Android Keystore. No plaintext key is persisted. */
class KeystoreApiSecrets(context: Context) : ApiSecrets {
    private val prefs = context.getSharedPreferences("yiji-api-secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized override fun read(id: String): String {
        val encoded = prefs.getString(id, null) ?: return ""
        return try {
            val fields = encoded.split(':')
            require(fields.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("API 密钥无法读取，请编辑配置并重新填写") }
    }
    @Synchronized override fun write(id: String, value: String) {
        require(value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() }) { "请填写有效的 API Key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(id, encrypted).commit()) { "密钥保存失败，请重试" }
    }
    @Synchronized override fun remove(id: String) { prefs.edit().remove(id).commit() }
    companion object { private const val ALIAS = "yiji-api-key-v2.1" }
}

data class ApiProfilesState(val profiles: List<ApiProfile>, val selectedId: String, val enabled: Boolean, val includeContext: Boolean,
    val crossSourceContext: Boolean = false) {
    val selected: ApiProfile? get() = profiles.firstOrNull { it.id == selectedId }
}

class ApiProfileStore(context: Context, private val secrets: ApiSecrets = KeystoreApiSecrets(context)) {
    private val prefs = context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    val current get() = mutable.value
    private fun load(): ApiProfilesState {
        val array = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrDefault(JSONArray())
        val profiles = (0 until array.length()).mapNotNull { i -> runCatching {
            val o = array.getJSONObject(i)
            ApiProfile(o.getString("id"), o.getString("name"), o.getString("baseUrl"), o.getString("model")).also { it.validate() }
        }.getOrNull() }.distinctBy { it.id }.ifEmpty { listOf(ApiProfile.glm) }
        val selected = prefs.getString("selected", profiles.first().id).orEmpty().takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id
        return ApiProfilesState(profiles, selected, prefs.getBoolean("enabled", false), prefs.getBoolean("includeContext", true),
            prefs.getBoolean("crossSourceContext", false))
    }
    private fun persist(value: ApiProfilesState) {
        val array = JSONArray(value.profiles.map { p -> JSONObject().put("id", p.id).put("name", p.name).put("baseUrl", p.baseUrl).put("model", p.model) })
        check(prefs.edit().putString("profiles", array.toString()).putString("selected", value.selectedId)
            .putBoolean("enabled", value.enabled).putBoolean("includeContext", value.includeContext)
            .putBoolean("crossSourceContext", value.crossSourceContext).commit()) { "配置保存失败，请重试" }
        mutable.value = value
    }
    @Synchronized fun save(profile: ApiProfile, apiKey: String?) {
        profile.validate()
        if (apiKey != null) secrets.write(profile.id, apiKey.trim())
        val value = current
        val profiles = if (value.profiles.any { it.id == profile.id }) value.profiles.map { if (it.id == profile.id) profile else it } else value.profiles + profile
        require(profiles.size <= 20) { "最多保存 20 套 API 配置" }
        persist(value.copy(profiles = profiles))
    }
    @Synchronized fun select(id: String) {
        require(current.profiles.any { it.id == id }) { "配置已不存在" }
        persist(current.copy(selectedId = id))
    }
    @Synchronized fun delete(id: String) {
        val remaining = current.profiles.filterNot { it.id == id }.ifEmpty { listOf(ApiProfile.glm) }
        val selected = current.selectedId.takeIf { s -> remaining.any { it.id == s } } ?: remaining.first().id
        persist(current.copy(profiles = remaining, selectedId = selected, enabled = if (current.selectedId == id) false else current.enabled))
        secrets.remove(id)
    }
    @Synchronized fun enable(enabled: Boolean) {
        if (enabled) require(key(current.selectedId).isNotBlank()) { "请先为当前配置填写 API Key" }
        persist(current.copy(enabled = enabled))
    }
    @Synchronized fun includeContext(value: Boolean) { persist(current.copy(includeContext = value)) }
    @Synchronized fun crossSourceContext(value: Boolean) { persist(current.copy(crossSourceContext = value)) }
    fun key(id: String): String = secrets.read(id)
}
