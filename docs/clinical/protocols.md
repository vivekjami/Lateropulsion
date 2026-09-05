# Exercise protocols (v1 library) — for clinical-lead review

Source of truth: `config/protocols/*.json`. This page summarises them for review and sign-off (IMPLEMENTATION Phase 0 exit criterion).

| Protocol | Position | Mode | Blocks | Gate it passes / requires | Stop rules |
|---|---|---|---|---|---|
| baseline-capture-v1 | supported sitting | A (cues off) | 60 s capture | — | standard |
| std-sitting-v3 | unsupported sitting | A | midline 120 s → hold 180 s → hold 180 s, 60 s rests | unlocks std-standing-v3 when TIB5 ≥ 60 % for 2 sessions and 0 balance losses | > 12 episodes in a hold |
| weight-shift-sitting-v1 | unsupported sitting | A | settle 60 s → shift right 120 s → shift left 120 s | unlocks standing when mean recovery ≤ 3 s for 2 sessions | standard |
| reach-to-target-v1 | unsupported sitting | A | reach front 120 s → reach paretic side 120 s | — | standard |
| std-standing-v3 | standing, parallel bars | A | seated settle 60 s → stand 90 s → stand 90 s | requires std-sitting-v3 gate; unlocks walking when TIB10 ≥ 80 % for 2 sessions and 0 losses | > 8 episodes |
| walking-to-target-v1 | walking, supervised | A | stand 45 s → walk 60 s → walk 60 s | requires std-standing-v3 gate (not overridable) | > 6 episodes |
| compensated-sitting-research-v1 | unsupported sitting | **B** | Mode A settle 60 s → compensated 120 s → compensated 120 s, k₀ = 0.4, performance-driven fade | — | > 10 episodes; SSQ gating |

Absolute rules enforced in software: supervision attestation, harness for standing/walking, 20 min cap, rest every 5 min, instant abort, Mode B only with a fading schedule and seated.

Clinical lead sign-off: __________________  Date: ________
