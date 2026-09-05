package com.lateropulsion.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppConfigTest {
    @Test
    fun `defaults are valid and match README section 13`() {
        val c = AppConfig()
        assertTrue(c.validate().isEmpty(), c.validate().toString())
        assertEquals(20, c.session.maxDurationMin)
        assertEquals(VisualMode.VERTICAL_REFERENCE, c.visual.defaultMode)
        assertEquals(0.6, c.visual.gainInitial)
        assertEquals(10.0, c.metrics.episodeEnterDeg)
        assertEquals(7.0, c.metrics.episodeExitDeg)
        assertEquals(1825, c.privacy.retentionDays)
    }

    @Test
    fun `README example config parses with snake_case keys`() {
        val json = """
        {
          "session": { "max_duration_min": 20, "default_block_duration_s": 120, "mandatory_rest_every_s": 300, "auto_stop_on_ssq_flag": true },
          "visual": { "default_mode": "VERTICAL_REFERENCE", "gain_initial": 0.6, "gain_min": 0.0, "gain_fade_rule": "PERFORMANCE_DRIVEN",
                      "gain_fade_step": 0.1, "gain_fade_gate_tib5": 60.0, "allow_error_augmentation": false },
          "metrics": { "sample_rate_hz": 100, "store_rate_hz": 50, "lowpass_cutoff_hz": 5.0, "band_primary_deg": 5.0, "band_secondary_deg": 10.0,
                       "episode_enter_deg": 10.0, "episode_exit_deg": 7.0, "episode_min_duration_s": 1.0 },
          "safety": { "require_supervision_attestation": true, "require_harness_for_standing": true, "walking_unlock_requires_standing_pass": true,
                      "motion_to_photon_abort_ms": 60 },
          "privacy": { "media_capture_default": "off", "retention_days": 1825, "export_deidentified_by_default": true }
        }
        """.trimIndent()
        val c = LpJson.strict.decodeFromString(AppConfig.serializer(), json)
        assertTrue(c.validate().isEmpty())
        assertEquals(60, c.safety.motionToPhotonAbortMs)
    }

    @Test
    fun `invalid values are reported`() {
        val bad = AppConfig(metrics = MetricsConfig(episodeEnterDeg = 5.0, episodeExitDeg = 7.0))
        assertTrue(bad.validate().any { it.contains("episode_exit_deg") })
    }
}
