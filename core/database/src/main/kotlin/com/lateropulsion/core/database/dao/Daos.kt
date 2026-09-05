package com.lateropulsion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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
import kotlinx.coroutines.flow.Flow

/** Search/list projection: joins the identity table only for the name column. */
data class PatientRow(
    val id: String,
    @androidx.room.ColumnInfo(name = "display_id") val displayId: String,
    val name: String?,
    val age: Int,
    val sex: String,
    @androidx.room.ColumnInfo(name = "lateropulsion_direction") val lateropulsionDirection: String,
    @androidx.room.ColumnInfo(name = "session_count") val sessionCount: Int,
    @androidx.room.ColumnInfo(name = "last_session_at") val lastSessionAt: Long?,
)

@Dao
interface PatientDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(p: PatientEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertIdentity(i: PatientIdentityEntity)
    @Update suspend fun update(p: PatientEntity)
    @Query("SELECT * FROM patient WHERE id = :id") suspend fun byId(id: String): PatientEntity?
    @Query("SELECT * FROM patient WHERE display_id = :displayId") suspend fun byDisplayId(displayId: String): PatientEntity?
    @Query("SELECT * FROM patient_identity WHERE patient_id = :id") suspend fun identity(id: String): PatientIdentityEntity?
    @Query("DELETE FROM patient_identity WHERE patient_id = :id") suspend fun deleteIdentity(id: String)
    @Query("DELETE FROM patient WHERE id = :id") suspend fun deletePatient(id: String)

    @Query(
        """
        SELECT p.id, p.display_id, i.name, p.age, p.sex, p.lateropulsion_direction,
               (SELECT COUNT(*) FROM session s WHERE s.patient_id = p.id) AS session_count,
               (SELECT MAX(s.started_at_utc) FROM session s WHERE s.patient_id = p.id) AS last_session_at
        FROM patient p LEFT JOIN patient_identity i ON i.patient_id = p.id
        WHERE p.erased_at IS NULL AND (p.display_id LIKE :q OR i.name_key LIKE :qLower OR i.mrn LIKE :q)
        ORDER BY last_session_at DESC, p.created_at DESC LIMIT :limit
        """,
    )
    suspend fun search(q: String, qLower: String, limit: Int): List<PatientRow>

    @Query(
        """
        SELECT p.id, p.display_id, i.name, p.age, p.sex, p.lateropulsion_direction,
               (SELECT COUNT(*) FROM session s WHERE s.patient_id = p.id) AS session_count,
               (SELECT MAX(s.started_at_utc) FROM session s WHERE s.patient_id = p.id) AS last_session_at
        FROM patient p LEFT JOIN patient_identity i ON i.patient_id = p.id
        WHERE p.erased_at IS NULL
        ORDER BY COALESCE(last_session_at, p.created_at) DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<PatientRow>>

    @Query(
        """
        SELECT p.id, p.display_id, i.name, p.age, p.sex, p.lateropulsion_direction,
               (SELECT COUNT(*) FROM session s WHERE s.patient_id = p.id) AS session_count,
               (SELECT MAX(s.started_at_utc) FROM session s WHERE s.patient_id = p.id) AS last_session_at
        FROM patient p JOIN patient_identity i ON i.patient_id = p.id
        WHERE p.erased_at IS NULL AND ((i.name_key = :nameKey AND ABS(p.age - :age) <= 2) OR (:mrn IS NOT NULL AND i.mrn = :mrn))
        """,
    )
    suspend fun possibleDuplicates(nameKey: String, age: Int, mrn: String?): List<PatientRow>

    @Query("SELECT * FROM id_sequence WHERE year = :year") suspend fun sequence(year: Int): IdSequenceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSequence(s: IdSequenceEntity)

    @Transaction
    suspend fun allocateSequence(year: Int): Int {
        val cur = sequence(year)?.nextValue ?: 1
        putSequence(IdSequenceEntity(year, cur + 1))
        return cur
    }
}

@Dao
interface BaselineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(b: BaselineEntity)
    @Update suspend fun update(b: BaselineEntity)
    @Query("SELECT * FROM baseline WHERE id = :id") suspend fun byId(id: String): BaselineEntity?
    @Query("SELECT * FROM baseline WHERE patient_id = :patientId AND is_current = 1 LIMIT 1") suspend fun current(patientId: String): BaselineEntity?
    @Query("SELECT * FROM baseline WHERE patient_id = :patientId ORDER BY recorded_at DESC") suspend fun history(patientId: String): List<BaselineEntity>
    @Query("UPDATE baseline SET is_current = 0 WHERE patient_id = :patientId") suspend fun clearCurrent(patientId: String)
    @Query("UPDATE baseline SET locked = 1 WHERE id = :id") suspend fun lock(id: String)
    @Query("DELETE FROM baseline WHERE patient_id = :patientId") suspend fun deleteForPatient(patientId: String)

    @Transaction
    suspend fun insertAsCurrent(b: BaselineEntity) {
        clearCurrent(b.patientId)
        insert(b.copy(isCurrent = true))
    }
}

@Dao
interface AssessmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(a: AssessmentEntity)
    @Query("SELECT * FROM assessment WHERE patient_id = :patientId ORDER BY recorded_at DESC") suspend fun forPatient(patientId: String): List<AssessmentEntity>
    @Query("SELECT * FROM assessment WHERE patient_id = :patientId AND scale_code = :code ORDER BY recorded_at DESC LIMIT 1") suspend fun latest(patientId: String, code: String): AssessmentEntity?
    @Query("DELETE FROM assessment WHERE patient_id = :patientId") suspend fun deleteForPatient(patientId: String)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(s: SessionEntity)
    @Update suspend fun update(s: SessionEntity)
    @Query("SELECT * FROM session WHERE id = :id") suspend fun byId(id: String): SessionEntity?
    @Query("SELECT * FROM session WHERE patient_id = :patientId ORDER BY session_number ASC") suspend fun forPatient(patientId: String): List<SessionEntity>
    @Query("SELECT * FROM session WHERE patient_id = :patientId ORDER BY session_number ASC") fun observeForPatient(patientId: String): Flow<List<SessionEntity>>
    @Query("SELECT COALESCE(MAX(session_number), 0) + 1 FROM session WHERE patient_id = :patientId") suspend fun nextSessionNumber(patientId: String): Int
    @Query("SELECT * FROM session WHERE end_reason IS NULL") suspend fun unfinished(): List<SessionEntity>
    @Query("SELECT COUNT(*) FROM session WHERE started_at_utc >= :dayStart") suspend fun countSince(dayStart: Long): Int
    @Query("SELECT COUNT(DISTINCT patient_id) FROM session WHERE started_at_utc >= :dayStart") suspend fun patientsSince(dayStart: Long): Int
    @Query("DELETE FROM session WHERE patient_id = :patientId") suspend fun deleteForPatient(patientId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBlocks(blocks: List<BlockResultEntity>)
    @Query("SELECT * FROM block_result WHERE session_id = :sessionId ORDER BY order_index") suspend fun blocks(sessionId: String): List<BlockResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSummary(s: SessionSummaryEntity)
    @Query("SELECT * FROM session_summary WHERE session_id = :sessionId") suspend fun summary(sessionId: String): SessionSummaryEntity?
    @Query(
        """
        SELECT ss.* FROM session_summary ss JOIN session s ON s.id = ss.session_id
        WHERE s.patient_id = :patientId AND (:protocolId IS NULL OR s.protocol_id = :protocolId)
        ORDER BY s.session_number ASC
        """,
    )
    suspend fun summariesForPatient(patientId: String, protocolId: String?): List<SessionSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEvents(events: List<SessionEventEntity>)
    @Query("SELECT * FROM session_event WHERE session_id = :sessionId ORDER BY t_ns") suspend fun events(sessionId: String): List<SessionEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTimeseries(t: TimeseriesFileEntity)
    @Query("SELECT * FROM timeseries_file WHERE session_id = :sessionId") suspend fun timeseries(sessionId: String): TimeseriesFileEntity?
    @Query("SELECT path FROM timeseries_file t JOIN session s ON s.id = t.session_id WHERE s.patient_id = :patientId") suspend fun timeseriesPathsForPatient(patientId: String): List<String>

    @Transaction
    suspend fun complete(
        session: SessionEntity,
        blocks: List<BlockResultEntity>,
        summary: SessionSummaryEntity,
        events: List<SessionEventEntity>,
        timeseries: TimeseriesFileEntity?,
    ) {
        update(session)
        insertBlocks(blocks)
        insertSummary(summary)
        insertEvents(events)
        if (timeseries != null) insertTimeseries(timeseries)
    }
}

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(m: MediaAssetEntity)
    @Query("SELECT * FROM media_asset WHERE session_id = :sessionId") suspend fun forSession(sessionId: String): List<MediaAssetEntity>
    @Query("SELECT * FROM media_asset WHERE patient_id = :patientId") suspend fun forPatient(patientId: String): List<MediaAssetEntity>
    @Query("SELECT * FROM media_asset WHERE retention_until <= :now") suspend fun expired(now: Long): List<MediaAssetEntity>
    @Query("DELETE FROM media_asset WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM media_asset WHERE patient_id = :patientId") suspend fun deleteForPatient(patientId: String)
}

/** Deliberately has no @Update or @Delete: the audit log is append-only. */
@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(e: AuditEventEntity)
    @Query("SELECT * FROM audit_event ORDER BY at DESC LIMIT :limit") suspend fun recent(limit: Int): List<AuditEventEntity>
    @Query("SELECT * FROM audit_event WHERE target_type = :type AND target_id = :id ORDER BY at DESC") suspend fun forTarget(type: String, id: String): List<AuditEventEntity>
    @Query("SELECT COUNT(*) FROM audit_event") suspend fun count(): Int
}

@Dao
interface ClinicianDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(c: ClinicianEntity)
    @Update suspend fun update(c: ClinicianEntity)
    @Query("SELECT * FROM clinician WHERE active = 1 ORDER BY display_name") suspend fun list(): List<ClinicianEntity>
    @Query("SELECT * FROM clinician WHERE id = :id") suspend fun byId(id: String): ClinicianEntity?
    @Query("SELECT COUNT(*) FROM clinician") suspend fun count(): Int
}
