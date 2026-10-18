package cn.yiji.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class MemoryApiSecrets : ApiSecrets {
    val keys = mutableMapOf<String, String>()
    override fun read(id: String) = keys[id].orEmpty()
    override fun write(id: String, value: String) { keys[id] = value }
    override fun remove(id: String) { keys.remove(id) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ApiProfilesTest {
    private lateinit var context: Context
    private lateinit var secrets: MemoryApiSecrets
    private lateinit var store: ApiProfileStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE).edit().clear().commit()
        secrets = MemoryApiSecrets()
        store = ApiProfileStore(context, secrets)
    }
    @Test fun firstInstallDefaultsToGlmButDoesNotTransmitWithoutConsent() {
        assertEquals("glm-4.7-flash", store.current.selected!!.model)
        assertEquals(ApiProfile.GLM_URL, store.current.selected!!.baseUrl)
        assertFalse(store.current.enabled)
    }
    @Test fun multipleProvidersCanBeSavedSwitchedAndReloaded() {
        val alternate = ApiProfile.silicon
        store.save(ApiProfile.glm, "fixture-key-a")
        store.save(alternate, "fixture-key-b")
        store.select(alternate.id)
        store.enable(true)
        val reloaded = ApiProfileStore(context, secrets)
        assertEquals(2, reloaded.current.profiles.size)
        assertEquals(alternate.id, reloaded.current.selectedId)
        assertEquals("fixture-key-b", reloaded.key(alternate.id))
        assertTrue(reloaded.current.enabled)
    }
    @Test fun editingModelWithBlankKeyPreservesTheCredential() {
        store.save(ApiProfile.glm, "fixture-key")
        store.save(ApiProfile.glm.copy(model = "custom-model"), null)
        assertEquals("fixture-key", store.key(ApiProfile.glm.id))
        assertEquals("custom-model", store.current.selected!!.model)
    }
    @Test fun deletingActiveProfileDisablesCloudAndDeletesItsKey() {
        store.save(ApiProfile.glm, "fixture-key")
        store.enable(true)
        store.delete(ApiProfile.glm.id)
        assertFalse(store.current.enabled)
        assertEquals("", store.key(ApiProfile.glm.id))
        assertEquals("glm-4.7-flash", store.current.selected!!.model)
    }
    @Test fun plainProfilePreferencesContainNoApiKey() {
        store.save(ApiProfile.glm, "fixture-key-sensitive")
        assertFalse(context.getSharedPreferences("yiji-api-profiles", Context.MODE_PRIVATE).all.toString().contains("fixture-key-sensitive"))
    }
    @Test fun cloudRequiresAKey() {
        assertTrue(runCatching { store.enable(true) }.isFailure)
        assertFalse(store.current.enabled)
    }
    @Test fun unsafeUrlsAndControlCharactersAreRejected() {
        listOf("http://example.org/v1", "https://user:secret@example.org/v1", "https://example.org/v1?key=secret",
            "https://example.org/v1#key", "not a url").forEach { url ->
            assertTrue(url, runCatching { ApiProfile(baseUrl = url).validate() }.isFailure)
        }
        assertTrue(runCatching { ApiProfile(model = "model\nbad").validate() }.isFailure)
    }
    @Test fun copiedCompletionUrlDoesNotDuplicateThePath() {
        val p = ApiProfile(baseUrl = ApiProfile.GLM_URL + "/chat/completions/")
        assertEquals(ApiProfile.GLM_URL + "/chat/completions", p.endpoint("chat/completions"))
        assertEquals(ApiProfile.GLM_URL + "/models", p.endpoint("models"))
    }
}
