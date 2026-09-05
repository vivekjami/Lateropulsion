package com.lateropulsion.core.datastore

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AssetConfigRepositoryTest {
    private val root = File(System.getProperty("lp.configDir")).parentFile
    private val source = object : AssetSource {
        override fun read(path: String): String? = File(root, path).takeIf { it.isFile }?.readText()
        override fun list(dir: String): List<String> = File(root, dir).list()?.toList() ?: emptyList()
    }

    @Test
    fun `REQ-SES-001 shipped config loads without errors`() = runTest {
        val repo = AssetConfigRepository(source)
        assertEquals(20, repo.appConfig().session.maxDurationMin)
        assertEquals(7, repo.protocols().size)
        assertEquals(8, repo.scales().size)
        assertEquals(2, repo.deviceProfiles().size)
        assertEquals(1, repo.headsetProfiles().size)
        assertTrue(repo.loadErrors.isEmpty(), repo.loadErrors.toString())
    }

    @Test
    fun `REQ-SAF-020 device profile resolution falls back to the generic profile for unknown phones`() = runTest {
        val repo = AssetConfigRepository(source)
        assertEquals("redmi-note-10s", repo.deviceProfileFor("M2101K7BI")!!.id)
        val generic = repo.deviceProfileFor("Pixel 8")
        assertNotNull(generic)
        assertEquals(AssetConfigRepository.GENERIC_ID, generic!!.id)
        assertTrue(!generic.qualified)
    }

    @Test
    fun `a broken protocol file is reported not fatal`() = runTest {
        val broken = object : AssetSource by source {
            override fun list(dir: String): List<String> = if (dir == "config/protocols") source.list(dir) + "bad.json" else source.list(dir)
            override fun read(path: String): String? = if (path.endsWith("bad.json")) "{\"protocol_id\": 3}" else source.read(path)
        }
        val repo = AssetConfigRepository(broken)
        assertEquals(7, repo.protocols().size)
        assertTrue(repo.loadErrors.any { it.startsWith("protocols/bad.json") })
    }
}
