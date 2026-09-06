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

    @Test
    fun `migrate 3 to 4 makes the listing metric columns nullable and keeps rows`() {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO block_result (id, session_id, block_id, order_index, exercise, position, started_mono_ns, duration_s, target_deg, tolerance_deg, gain, " +
                    "cues_json, metrics_json, episode_stats_json, episodes_json, checkpoints_json, end_reason, filter_params_json, mad_deg, tib5_pct, valid_sample_pct) " +
                    "VALUES ('b1', 's1', 'hold-1', 0, 'SITTING_HOLD', 'SITTING_UNSUPPORTED', 1, 180.0, 0.0, 5.0, 0.0, '[]', '{}', '{}', '[]', '[]', 'COMPLETED', '{}', 4.0, 55.0, 90.0)",
            )
            db.execSQL(
                "INSERT INTO session_summary (session_id, metrics_json, episodes_json, low_confidence, balance_loss_events, gain_used, visual_mode, end_reason, " +
                    "generated_by_version, generated_at, mad_deg, tib5_pct) VALUES ('s1', '{}', '{}', 0, 0, 0.0, 'VERTICAL_REFERENCE', 'COMPLETED', '1', 1, 4.0, 55.0)",
            )
        }
        val db = helper.runMigrationsAndValidate(name, 4, true, LpDatabase.MIGRATION_1_2, LpDatabase.MIGRATION_2_3, LpDatabase.MIGRATION_3_4)
        db.query("SELECT mad_deg, tib5_pct, valid_sample_pct FROM block_result WHERE id = 'b1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(4.0, c.getDouble(0), 1e-9); assertEquals(90.0, c.getDouble(2), 1e-9)
        }
        db.query("SELECT mad_deg FROM session_summary WHERE session_id = 's1'").use { c -> assertTrue(c.moveToFirst()); assertEquals(4.0, c.getDouble(0), 1e-9) }
        // a calm block: undefined metrics are stored as NULL instead of failing the save
        db.execSQL(
            "INSERT INTO block_result (id, session_id, block_id, order_index, exercise, position, started_mono_ns, duration_s, target_deg, tolerance_deg, gain, " +
                "cues_json, metrics_json, episode_stats_json, episodes_json, checkpoints_json, end_reason, filter_params_json, mad_deg, tib5_pct, valid_sample_pct) " +
                "VALUES ('b2', 's1', 'hold-2', 1, 'SITTING_HOLD', 'SITTING_UNSUPPORTED', 1, 8.0, 0.0, 5.0, 0.0, '[]', '{}', '{}', '[]', '[]', 'ABORTED', '{}', NULL, NULL, NULL)",
        )
        db.query("SELECT mad_deg FROM block_result WHERE id = 'b2'").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        db.close()
    }
}
