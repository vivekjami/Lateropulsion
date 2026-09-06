package com.lateropulsion.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.lateropulsion.core.database.dao.AssessmentDao
import com.lateropulsion.core.database.dao.AuditDao
import com.lateropulsion.core.database.dao.BaselineDao
import com.lateropulsion.core.database.dao.ClinicianDao
import com.lateropulsion.core.database.dao.MediaDao
import com.lateropulsion.core.database.dao.PatientDao
import com.lateropulsion.core.database.dao.SessionDao
import com.lateropulsion.core.database.entity.AssessmentEntity
import com.lateropulsion.core.database.entity.AuditEventEntity
import com.lateropulsion.core.database.entity.BaselineEntity
import com.lateropulsion.core.database.entity.BlockResultEntity
import com.lateropulsion.core.database.entity.ClinicianEntity
import com.lateropulsion.core.database.entity.IdSequenceEntity
import com.lateropulsion.core.database.entity.MediaAssetEntity
import com.lateropulsion.core.database.entity.PatientEntity
import com.lateropulsion.core.database.entity.PatientIdentityEntity
import com.lateropulsion.core.database.entity.SessionEntity
import com.lateropulsion.core.database.entity.SessionEventEntity
import com.lateropulsion.core.database.entity.SessionSummaryEntity
import com.lateropulsion.core.database.entity.TimeseriesFileEntity

@Database(
    entities = [
        ClinicianEntity::class, PatientEntity::class, PatientIdentityEntity::class, BaselineEntity::class,
        AssessmentEntity::class, SessionEntity::class, BlockResultEntity::class, SessionSummaryEntity::class,
        SessionEventEntity::class, TimeseriesFileEntity::class, MediaAssetEntity::class, AuditEventEntity::class,
        IdSequenceEntity::class,
    ],
    version = LpDatabase.VERSION,
    exportSchema = true,
)
abstract class LpDatabase : RoomDatabase() {
    abstract fun patients(): PatientDao
    abstract fun baselines(): BaselineDao
    abstract fun assessments(): AssessmentDao
    abstract fun sessions(): SessionDao
    abstract fun media(): MediaDao
    abstract fun audit(): AuditDao
    abstract fun clinicians(): ClinicianDao

    companion object {
        const val VERSION = 4
        const val FILE_NAME = "lateropulsion.db"

        /** v1 → v2: consent form version on the patient record (DPDP consent traceability). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE patient ADD COLUMN consent_version TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3: the picture tilt applied in each session (ADR-022). */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session ADD COLUMN applied_tilt_deg REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4: the denormalised metric columns of block_result and session_summary become nullable. A calm block or session has
         * undefined metrics (NaN), which SQLite stores as NULL, and the NOT NULL columns refused the whole save. SQLite cannot
         * drop a NOT NULL constraint in place, so each table is rebuilt and its rows copied.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `block_result_v4` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `block_id` TEXT NOT NULL, " +
                        "`order_index` INTEGER NOT NULL, `exercise` TEXT NOT NULL, `position` TEXT NOT NULL, `started_mono_ns` INTEGER NOT NULL, " +
                        "`duration_s` REAL NOT NULL, `target_deg` REAL NOT NULL, `tolerance_deg` REAL NOT NULL, `gain` REAL NOT NULL, " +
                        "`cues_json` TEXT NOT NULL, `metrics_json` TEXT NOT NULL, `episode_stats_json` TEXT NOT NULL, `episodes_json` TEXT NOT NULL, " +
                        "`checkpoints_json` TEXT NOT NULL, `end_reason` TEXT NOT NULL, `filter_params_json` TEXT NOT NULL, `mad_deg` REAL, `tib5_pct` REAL, " +
                        "`valid_sample_pct` REAL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("INSERT INTO `block_result_v4` SELECT * FROM `block_result`")
                db.execSQL("DROP TABLE `block_result`")
                db.execSQL("ALTER TABLE `block_result_v4` RENAME TO `block_result`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_block_result_session_id` ON `block_result` (`session_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_summary_v4` (`session_id` TEXT NOT NULL, `baseline_id` TEXT, `baseline_mad_deg` REAL, " +
                        "`metrics_json` TEXT NOT NULL, `episodes_json` TEXT NOT NULL, `delta_deg` REAL, `improvement_pct` REAL, `within_mdc` INTEGER, " +
                        "`mdc_deg` REAL, `low_confidence` INTEGER NOT NULL, `comparison_refused_reason` TEXT, `balance_loss_events` INTEGER NOT NULL, " +
                        "`assistance_level` INTEGER, `gain_used` REAL NOT NULL, `visual_mode` TEXT NOT NULL, `end_reason` TEXT NOT NULL, " +
                        "`generated_by_version` TEXT NOT NULL, `generated_at` INTEGER NOT NULL, `mad_deg` REAL, `tib5_pct` REAL, PRIMARY KEY(`session_id`), " +
                        "FOREIGN KEY(`session_id`) REFERENCES `session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("INSERT INTO `session_summary_v4` SELECT * FROM `session_summary`")
                db.execSQL("DROP TABLE `session_summary`")
                db.execSQL("ALTER TABLE `session_summary_v4` RENAME TO `session_summary`")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /** Production: SQLCipher-encrypted file database. `factory` comes from [com.lateropulsion.core.database.security.EncryptedOpenHelperFactory]. */
        fun encrypted(context: Context, factory: SupportSQLiteOpenHelper.Factory): LpDatabase =
            Room.databaseBuilder(context, LpDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .addMigrations(*ALL_MIGRATIONS)
                .build()

        /** Tests only: plain in-memory database. Never used by a shipped flavour. */
        fun inMemory(context: Context): LpDatabase =
            Room.inMemoryDatabaseBuilder(context, LpDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
