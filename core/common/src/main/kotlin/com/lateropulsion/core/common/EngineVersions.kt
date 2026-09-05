package com.lateropulsion.core.common

/**
 * Algorithm versions stamped into stored results so that old summaries can be recomputed
 * and diffed when an algorithm changes (ARCHITECTURE §10 schema notes).
 */
public object EngineVersions {
    public const val METRICS_ENGINE: String = "metrics-1.0.0"
    public const val PROTOCOL_ENGINE: String = "protocol-1.0.0"
    public const val FUSION: String = "fusion-1.0.0"
    public const val LPX_FORMAT_VERSION: Int = 1
}
