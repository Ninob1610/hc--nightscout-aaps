package de.nbeck.aapshc

import de.nbeck.aapshc.ns.NightscoutAuthException
import de.nbeck.aapshc.ns.NightscoutClient
import de.nbeck.aapshc.ns.Permissions
import de.nbeck.aapshc.ns.Treatment
import de.nbeck.aapshc.ns.UpsertResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NightscoutTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun serve(permissions: String, putCode: Int = 201, knownToken: Boolean = true, storedDate: Long? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/v3/version" -> MockResponse().setBody("""{"status":200,"result":{"version":"15.0.3"}}""")
                    path.startsWith("/api/v2/authorization/request/") ->
                        if (knownToken) MockResponse().setBody("""{"token":"jwt","sub":"healthconnect","permissionGroups":[$permissions,["*:*:read"]]}""")
                        else MockResponse().setResponseCode(401).setBody("""{"status":401}""")
                    path.startsWith("/api/v3/entries") ->
                        MockResponse().setBody("""{"status":200,"result":[{"date":2000,"sgv":110},{"date":1000,"mbg":95},{"date":3000,"sgv":64}]}""")
                    request.method == "GET" && storedDate != null && path.startsWith("/api/v3/treatments/") ->
                        MockResponse().setBody("""{"status":200,"result":{"date":$storedDate,"utcOffset":60}}""")
                    request.method == "PUT" ->
                        if (request.getHeader("Authorization") != "Bearer jwt") MockResponse().setResponseCode(401)
                        else if (storedDate != null && !request.body.readUtf8().contains("\"date\":$storedDate"))
                            MockResponse().setResponseCode(400).setBody("""{"status":400,"message":"Field date cannot be modified by the client"}""")
                        else MockResponse().setResponseCode(putCode).setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun client() = NightscoutClient(server.url("/").toString(), "healthconnect-abc")

    private val treatment = Treatment("id-1", Treatment.EXERCISE, 1_000L, 60, 30, "Laufen")

    @Test
    fun careportalRoleLacksUpdateAndDelete() = runTest {
        serve("""["api:treatments:create"]""")
        val info = client().testConnection()
        assertEquals("15.0.3", info.version)
        assertEquals("healthconnect", info.subject)
        assertEquals(listOf(Permissions.UPDATE, Permissions.DELETE), info.missing)
    }

    @Test
    fun treatmentsWildcardIsEnough() = runTest {
        serve("""["api:treatments:*"]""")
        assertTrue(client().testConnection().missing.isEmpty())
        assertEquals(UpsertResult.WRITTEN, client().upsert(treatment))
    }

    @Test
    fun unknownTokenGivesClearMessage() = runTest {
        serve("[]", knownToken = false)
        try {
            client().testConnection()
            fail("Exception erwartet")
        } catch (e: NightscoutAuthException) {
            assertTrue(e.message!!.contains("API_SECRET"))
        }
    }

    @Test
    fun goneMeansDeletedInAaps() = runTest {
        serve("""["*"]""", putCode = 410)
        assertEquals(UpsertResult.DELETED_REMOTELY, client().upsert(treatment))
    }

    @Test
    fun immutableDateIsTakenFromServer() = runTest {
        serve("""["api:treatments:*"]""", putCode = 200, storedDate = 500L)
        assertEquals(UpsertResult.WRITTEN, client().upsert(treatment))
        assertEquals(4, server.requestCount) // Auth, PUT (400), GET, PUT mit Server-date
    }

    @Test
    fun forbiddenIsAuthError() = runTest {
        serve("""["api:treatments:create"]""", putCode = 403)
        try {
            client().upsert(treatment)
            fail("Exception erwartet")
        } catch (e: NightscoutAuthException) {
            assertTrue(e.message!!, e.message!!.contains(Permissions.UPDATE))
        }
    }

    @Test
    fun glucoseOnlyReadsSgvAndChecksPermission() = runTest {
        serve("""["api:treatments:*"]""")
        val c = client()
        assertEquals(listOf(2000L to 110, 3000L to 64), c.glucose(0, 5000).map { it.date to it.mgdl })
        assertTrue(c.canReadEntries()) // *:*:read aus der Standardrolle
        val path = server.takeRequestPaths().single { it.startsWith("/api/v3/entries") }
        assertTrue(path, path.contains("date\$gte=0") && path.contains("date\$lte=5000"))
    }

    private fun MockWebServer.takeRequestPaths(): List<String> =
        generateSequence { takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS)?.path }.toList()

    @Test
    fun shiroMatching() {
        assertTrue(Permissions.implies("*", Permissions.DELETE))
        assertTrue(Permissions.implies("api:treatments", Permissions.UPDATE))
        assertTrue(Permissions.implies("api:treatments:create,update", Permissions.UPDATE))
        assertTrue(Permissions.implies("api:*:update", Permissions.UPDATE))
        assertFalse(Permissions.implies("*:*:read", Permissions.UPDATE))
        assertFalse(Permissions.implies("api:entries:*", Permissions.UPDATE))
        assertFalse(Permissions.implies("api:treatments:create:x", Permissions.CREATE))
    }

    @Test
    fun urlNormalization() {
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl(" ns.example.com/ "))
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl("https://ns.example.com/api/v1/?token=abc"))
        assertEquals("http://192.168.1.5:1337", NightscoutClient.normalizeUrl("http://192.168.1.5:1337/api/v3"))
    }
}
