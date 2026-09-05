package com.lateropulsion.core.common

/**
 * Result type used across all layers instead of exceptions for expected failures.
 * Exceptions are reserved for programming errors.
 */
public sealed interface Outcome<out T> {
    public data class Success<T>(val value: T) : Outcome<T>
    public data class Failure(val error: LpError) : Outcome<Nothing>

    public val isSuccess: Boolean get() = this is Success
    public val isFailure: Boolean get() = this is Failure

    public fun getOrNull(): T? = (this as? Success)?.value
    public fun errorOrNull(): LpError? = (this as? Failure)?.error

    public fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw LpException(error)
    }

    public companion object {
        public fun <T> success(value: T): Outcome<T> = Success(value)
        public fun failure(error: LpError): Outcome<Nothing> = Failure(error)
    }
}

public inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

public inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

public inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

public inline fun <T> Outcome<T>.onFailure(block: (LpError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

public inline fun <T, R> Outcome<T>.fold(onSuccess: (T) -> R, onFailure: (LpError) -> R): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

public fun <T> Outcome<T>.getOrDefault(default: T): T = getOrNull() ?: default

/** Wraps a throwing block into an [Outcome]; only for adapting third-party IO. */
public inline fun <T> runOutcome(onError: (Throwable) -> LpError = { LpError.Unexpected(it) }, block: () -> T): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (e: LpException) {
        Outcome.Failure(e.error)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Outcome.Failure(onError(e))
    }

/** Domain error taxonomy. Every error carries a stable [code] for logging and UI mapping. */
public sealed class LpError(public val code: String) {
    public abstract val message: String
    public open val cause: Throwable? get() = null

    public data class Validation(val field: String, override val message: String) : LpError("VALIDATION")
    public data class NotFound(val entity: String, val id: String) : LpError("NOT_FOUND") {
        override val message: String get() = "$entity not found: ${Redaction.shortId(id)}"
    }
    public data class Conflict(override val message: String) : LpError("CONFLICT")
    public data class Precondition(override val message: String) : LpError("PRECONDITION")
    public data class Safety(override val message: String) : LpError("SAFETY")
    public data class Security(override val message: String) : LpError("SECURITY")
    public data class Io(override val message: String, override val cause: Throwable? = null) : LpError("IO")
    public data class Unexpected(override val cause: Throwable?, override val message: String = cause?.message ?: "unexpected") :
        LpError("UNEXPECTED")
}

public class LpException(public val error: LpError) : RuntimeException("${error.code}: ${error.message}", error.cause)

/** Collects several validation errors so a form can show them all at once. */
public class ValidationErrors {
    private val errors = mutableListOf<LpError.Validation>()
    public fun require(condition: Boolean, field: String, message: String): ValidationErrors {
        if (!condition) errors += LpError.Validation(field, message)
        return this
    }
    public fun add(error: LpError.Validation): ValidationErrors { errors += error; return this }
    public val isEmpty: Boolean get() = errors.isEmpty()
    public fun toList(): List<LpError.Validation> = errors.toList()
    public fun <T> toOutcome(value: () -> T): Outcome<T> =
        if (errors.isEmpty()) Outcome.Success(value()) else Outcome.Failure(errors.first())
}
