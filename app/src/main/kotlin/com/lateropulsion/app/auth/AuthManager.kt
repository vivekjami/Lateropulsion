package com.lateropulsion.app.auth

import com.lateropulsion.core.common.Clock
import com.lateropulsion.core.common.LpError
import com.lateropulsion.core.common.Outcome
import com.lateropulsion.core.model.AuditAction
import com.lateropulsion.core.model.AuditEvent
import com.lateropulsion.core.model.AuditRepository
import com.lateropulsion.core.model.Clinician
import com.lateropulsion.core.model.ClinicianId
import com.lateropulsion.core.model.ClinicianRole
import com.lateropulsion.core.model.Ids
import com.lateropulsion.core.model.SecurityConfig
import com.lateropulsion.core.database.repo.ClinicianRepositoryImpl
import com.lateropulsion.core.database.repo.CurrentClinician
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** PBKDF2-HMAC-SHA256 PIN hashing; the PIN itself is never stored (REQ-SEC-003). */
object PinHasher {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hash(pin: String, saltHex: String): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, saltHex: String, expectedHex: String): Boolean {
        val a = hash(pin, saltHex).toByteArray(); val b = expectedHex.toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

sealed interface AuthState {
    data object Loading : AuthState
    data object NoAccount : AuthState
    data class Locked(val clinicians: List<Clinician>, val message: String? = null) : AuthState
    data class Unlocked(val clinician: Clinician) : AuthState
}

@Singleton
class AuthManager @Inject constructor(
    private val clinicians: ClinicianRepositoryImpl,
    private val audit: AuditRepository,
    private val clock: Clock,
    private val security: SecurityConfig,
) : CurrentClinician {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> get() = _state
    @Volatile private var lastActivityMs = 0L

    override fun id(): ClinicianId? = (_state.value as? AuthState.Unlocked)?.clinician?.id
    val current: Clinician? get() = (_state.value as? AuthState.Unlocked)?.clinician

    suspend fun refresh() {
        val list = clinicians.list()
        _state.value = when {
            _state.value is AuthState.Unlocked -> _state.value
            list.isEmpty() -> AuthState.NoAccount
            else -> AuthState.Locked(list)
        }
    }

    suspend fun createAccount(name: String, role: ClinicianRole, pin: String): Outcome<Clinician> {
        if (pin.length < security.pinMinLength || !pin.all { it.isDigit() }) return Outcome.failure(LpError.Validation("pin", "PIN must be at least ${security.pinMinLength} digits"))
        val salt = PinHasher.newSalt()
        val c = Clinician(Ids.clinician(), name.trim(), role, clock.nowUtcMillis())
        return clinicians.create(c, PinHasher.hash(pin, salt), salt).also { r -> if (r.isSuccess) { _state.value = AuthState.Unlocked(c); touch(); log(AuditAction.LOGIN, c.id) } }
    }

    suspend fun unlock(id: ClinicianId, pin: String): Outcome<Clinician> {
        val c = clinicians.findById(id) ?: return Outcome.failure(LpError.NotFound("clinician", id.value))
        val lockedUntil = clinicians.lockedUntil(id)
        if (lockedUntil > clock.nowUtcMillis()) return Outcome.failure(LpError.Security("Locked out for ${(lockedUntil - clock.nowUtcMillis()) / 1000} s"))
        val (hash, salt) = clinicians.credentials(id) ?: return Outcome.failure(LpError.Security("No credentials"))
        if (!PinHasher.verify(pin, salt, hash)) {
            val n = clinicians.recordFailedAttempt(id)
            if (n >= security.maxFailedAttempts) {
                clinicians.setLockedUntil(id, clock.nowUtcMillis() + security.lockoutSeconds * 1000L)
                clinicians.resetFailedAttempts(id)
                log(AuditAction.LOCK, id, "lockout after $n failed attempts")
                return Outcome.failure(LpError.Security("Too many attempts. Locked for ${security.lockoutSeconds} s"))
            }
            return Outcome.failure(LpError.Security("Wrong PIN ($n of ${security.maxFailedAttempts})"))
        }
        clinicians.resetFailedAttempts(id)
        _state.value = AuthState.Unlocked(c)
        touch()
        log(AuditAction.LOGIN, id)
        return Outcome.success(c)
    }

    /** Biometric success re-uses the clinician chosen on the lock screen; the PIN is still the enrolment credential. */
    suspend fun unlockWithBiometric(id: ClinicianId): Outcome<Clinician> {
        val c = clinicians.findById(id) ?: return Outcome.failure(LpError.NotFound("clinician", id.value))
        _state.value = AuthState.Unlocked(c); touch(); log(AuditAction.LOGIN, id, "biometric")
        return Outcome.success(c)
    }

    suspend fun lock(reason: String = "manual") {
        val who = id()
        val list = clinicians.list()
        _state.value = if (list.isEmpty()) AuthState.NoAccount else AuthState.Locked(list, if (reason == "auto") "Locked after inactivity" else null)
        who?.let { log(AuditAction.LOGOUT, it, reason) }
    }

    fun touch() { lastActivityMs = clock.nowUtcMillis() }

    /** Called by the process-lifecycle observer and a UI heartbeat (REQ-SEC-003). */
    suspend fun autoLockIfIdle(inBackground: Boolean) {
        if (_state.value !is AuthState.Unlocked) return
        val idleMs = clock.nowUtcMillis() - lastActivityMs
        if (inBackground || idleMs > security.autoLockSeconds * 1000L) lock("auto")
    }

    private suspend fun log(action: AuditAction, id: ClinicianId, detail: String = "") {
        audit.record(AuditEvent(Ids.audit(), id, clock.nowUtcMillis(), action, "clinician", id.value, detail))
    }
}
