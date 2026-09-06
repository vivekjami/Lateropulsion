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
        const val VERSION = 3
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

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
