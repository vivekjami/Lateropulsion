package com.lateropulsion.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** REQ-DAT-003: every schema bump ships with a migration verified against the exported history. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LpDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val name = "migration-test.db"

    @Test
    fun `migrate 1 to 2 preserves patients and adds consent_version`() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                """INSERT INTO patient (id, display_id, age, sex, diagnosis, lesion_side, affected_side, lateropulsion_direction, onset_epoch_day,
                   consent_media, consent_research, consent_recorded_at, advanced_protocol_allowed, notes, created_at, updated_at, erased_at)
                   VALUES ('p1','LP-2026-0001',60,'MALE','stroke','RIGHT','LEFT','LEFT',NULL,0,1,1,0,'',1,1,NULL)""",
            )
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, LpDatabase.MIGRATION_1_2)
        db.query("SELECT display_id, consent_version FROM patient").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("LP-2026-0001", c.getString(0))
            assertEquals("", c.getString(1))
        }
        db.close()
    }

    @Test
    fun `migrate 2 to 3 adds applied_tilt_deg with default 0`() {
        helper.createDatabase(name, 2).close()
        val db = helper.runMigrationsAndValidate(name, 3, true, LpDatabase.MIGRATION_1_2, LpDatabase.MIGRATION_2_3)
        db.query("PRAGMA table_info(session)").use { c ->
            val cols = generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
            assertTrue("applied_tilt_deg" in cols)
        }
        db.close()
    }
}
