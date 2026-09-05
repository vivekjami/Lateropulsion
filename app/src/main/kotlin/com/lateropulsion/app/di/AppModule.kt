package com.lateropulsion.app.di

import android.content.Context
import android.os.SystemClock
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.DefaultDispatchers
import com.lateropulsion.core.common.LpDispatchers
import com.lateropulsion.core.database.LpDatabase
import com.lateropulsion.core.database.repo.AssessmentRepositoryImpl
import com.lateropulsion.core.database.repo.AuditRepositoryImpl
import com.lateropulsion.core.database.repo.Auditor
import com.lateropulsion.core.database.repo.BaselineRepositoryImpl
import com.lateropulsion.core.database.repo.ClinicianRepositoryImpl
import com.lateropulsion.core.database.repo.CurrentClinician
import com.lateropulsion.core.database.repo.MediaRepositoryImpl
import com.lateropulsion.core.database.repo.PatientRepositoryImpl
import com.lateropulsion.core.database.repo.SessionRepositoryImpl
import com.lateropulsion.core.database.security.DatabaseKeyManager
import com.lateropulsion.core.database.security.EncryptedOpenHelperFactory
import com.lateropulsion.core.datastore.AssetConfigRepository
import com.lateropulsion.core.datastore.AssetSource
import com.lateropulsion.core.datastore.SettingsStore
import com.lateropulsion.core.model.AppConfig
import com.lateropulsion.core.model.AssessmentRepository
import com.lateropulsion.core.model.AuditRepository
import com.lateropulsion.core.model.BaselineRepository
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.ConfigRepository
import com.lateropulsion.core.model.MediaRepository
import com.lateropulsion.core.model.PatientRepository
import com.lateropulsion.core.model.SecurityConfig
import com.lateropulsion.core.model.SessionRepository
import com.lateropulsion.engine.render.AbortController
import com.lateropulsion.engine.render.RenderStateHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Singleton

/** Android clock: sensor timestamps and Choreographer share `elapsedRealtimeNanos` (REQ-SES-021). */
object AndroidClock : Clock {
    override fun nowUtcMillis(): Long = System.currentTimeMillis()
    override fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Breaks the AuthManager ↔ Auditor cycle: who is logged in, readable by every repository. */
@Singleton
class CurrentClinicianHolder @javax.inject.Inject constructor() : CurrentClinician {
    @Volatile var current: ClinicianId? = null
    override fun id(): ClinicianId? = current
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun clock(): Clock = AndroidClock
    @Provides @Singleton fun dispatchers(): LpDispatchers = DefaultDispatchers(Dispatchers.Main)
    @Provides @Singleton fun settings(@ApplicationContext ctx: Context): SettingsStore = SettingsStore(ctx)

    @Provides @Singleton
    fun configRepository(@ApplicationContext ctx: Context): AssetConfigRepository = AssetConfigRepository(object : AssetSource {
        override fun read(path: String): String? = runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
        override fun list(dir: String): List<String> = runCatching { ctx.assets.list(dir)?.toList() }.getOrNull() ?: emptyList()
    })

    @Provides fun configRepositoryApi(repo: AssetConfigRepository): ConfigRepository = repo

    /** app.json is 2 KB; reading it once at first injection is acceptable. */
    @Provides @Singleton fun appConfig(repo: AssetConfigRepository): AppConfig = runBlocking { repo.appConfig() }
    @Provides fun securityConfig(cfg: AppConfig): SecurityConfig = cfg.security

    @Provides @Singleton fun renderStates(): RenderStateHolder = RenderStateHolder()
    @Provides @Singleton fun abortController(): AbortController = AbortController()
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): LpDatabase =
        LpDatabase.encrypted(ctx, EncryptedOpenHelperFactory.create(DatabaseKeyManager(ctx).passphrase()))

    @Provides @Singleton fun auditor(db: LpDatabase, clock: Clock, who: CurrentClinicianHolder): Auditor = Auditor(db, clock, who)

    @Provides @Singleton
    fun patients(db: LpDatabase, clock: Clock, auditor: Auditor, @ApplicationContext ctx: Context): PatientRepository =
        PatientRepositoryImpl(db, clock, auditor) { path -> File(path).takeIf { it.startsWith(ctx.filesDir) || it.startsWith(ctx.noBackupFilesDir) }?.delete() }

    @Provides @Singleton fun baselines(db: LpDatabase, auditor: Auditor): BaselineRepository = BaselineRepositoryImpl(db, auditor)
    @Provides @Singleton fun assessments(db: LpDatabase, auditor: Auditor): AssessmentRepository = AssessmentRepositoryImpl(db, auditor)
    @Provides @Singleton fun sessions(db: LpDatabase, auditor: Auditor): SessionRepository = SessionRepositoryImpl(db, auditor)
    @Provides @Singleton fun audit(db: LpDatabase): AuditRepository = AuditRepositoryImpl(db)
    @Provides @Singleton fun clinicians(db: LpDatabase, clock: Clock, auditor: Auditor): ClinicianRepositoryImpl = ClinicianRepositoryImpl(db, clock, auditor)
    @Provides @Singleton fun media(db: LpDatabase, auditor: Auditor): MediaRepository = MediaRepositoryImpl(db, auditor)
}

/** Keeps the singleton holder in step with the auth state. */
@Singleton
class AuthBinder @javax.inject.Inject constructor(val auth: AuthManager, private val holder: CurrentClinicianHolder) {
    suspend fun bind() {
        auth.state.collect { s -> holder.current = (s as? com.lateropulsion.app.auth.AuthState.Unlocked)?.clinician?.id }
    }
}
