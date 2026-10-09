package com.oxipro.bridge.health

/**
 * Evaluates blood pressure / heart rate readings against published reference
 * ranges and returns a traffic-light colour + short feedback message.
 *
 * Sources:
 *  - Systolic/diastolic thresholds: 2017 ACC/AHA guideline (reaffirmed in the
 *    2025 update) — Normal <120/<80, Elevated 120-129/<80, Stage 1
 *    130-139/80-89, Stage 2 >=140/>=90, Hypertensive crisis >180/>120.
 *    Low blood pressure is commonly defined as <90/<60.
 *  - Resting heart rate: AHA normal resting range is 60-100 bpm.
 *  - Pulse pressure (systolic - diastolic): normal range is roughly
 *    30-50 mmHg (Cleveland Clinic / American Heart Association). Below ~25
 *    mmHg is a "narrow" pulse pressure (associated with low stroke volume /
 *    poor heart function); above ~60 mmHg is a "wide" pulse pressure
 *    (associated with arterial stiffness and higher cardiovascular risk).
 *    This is a U-shaped risk curve, not a straight low-to-high gradient —
 *    both extremes warrant more attention than the middle.
 *
 * This is general educational information, not a diagnosis. Anyone with a
 * persistently abnormal reading should talk to a clinician.
 */
object BpEvaluator {

    // Material-ish colours chosen for contrast against a dark background.
    const val COLOR_GOOD = "#66BB6A"    // green
    const val COLOR_CAUTION = "#FFCA28" // amber/yellow
    const val COLOR_CONCERN = "#EF5350" // red

    data class FieldResult(val label: String, val value: String, val colorHex: String)

    data class Assessment(
        val systolic: FieldResult,
        val diastolic: FieldResult,
        val pulse: FieldResult,
        val pulsePressure: FieldResult,
        val overallCategory: String,
        val feedback: String
    )

    fun evaluate(systolicMmHg: Int, diastolicMmHg: Int, pulseBpm: Int): Assessment {
        val pulsePressure = systolicMmHg - diastolicMmHg

        val systolicField = FieldResult(
            "Systolic", "$systolicMmHg mmHg",
            when {
                systolicMmHg < 90 -> COLOR_CAUTION
                systolicMmHg < 120 -> COLOR_GOOD
                else -> COLOR_CONCERN
            }
        )

        val diastolicField = FieldResult(
            "Diastolic", "$diastolicMmHg mmHg",
            when {
                diastolicMmHg < 60 -> COLOR_CAUTION
                diastolicMmHg < 80 -> COLOR_GOOD
                else -> COLOR_CONCERN
            }
        )

        val pulseField = FieldResult(
            "Pulse", "$pulseBpm bpm",
            when {
                pulseBpm < 60 -> COLOR_CAUTION
                pulseBpm <= 100 -> COLOR_GOOD
                else -> COLOR_CONCERN
            }
        )

        val pulsePressureField = FieldResult(
            "Pulse pressure", "$pulsePressure mmHg",
            when {
                pulsePressure < 25 || pulsePressure >= 100 -> COLOR_CONCERN
                pulsePressure in 25..29 || pulsePressure in 60..99 -> COLOR_CAUTION
                else -> COLOR_GOOD
            }
        )

        val (category, feedback) = categorize(systolicMmHg, diastolicMmHg, pulseBpm, pulsePressure)

        return Assessment(systolicField, diastolicField, pulseField, pulsePressureField, category, feedback)
    }

    private fun categorize(sys: Int, dia: Int, pulse: Int, pulsePressure: Int): Pair<String, String> {
        val bpNote = when {
            sys > 180 || dia > 120 ->
                "Hypertensive crisis range" to
                    "This is well above normal. If you also have chest pain, shortness of breath, " +
                    "vision changes, or severe headache, seek medical attention promptly. Otherwise, " +
                    "rest a few minutes and re-check — if it's still this high, contact a doctor."
            sys >= 140 || dia >= 90 ->
                "Stage 2 hypertension range" to
                    "This is elevated enough that it's worth discussing with a doctor, especially if " +
                    "you're seeing this consistently rather than a one-off."
            sys in 130..139 || dia in 80..89 ->
                "Stage 1 hypertension range" to
                    "A bit above the healthy range. Worth keeping an eye on with repeat readings, and " +
                    "mentioning to a doctor if it stays here."
            sys in 120..129 && dia < 80 ->
                "Elevated" to
                    "Slightly above the ideal range but not yet in hypertension territory — a good " +
                    "nudge to keep tabs on it."
            sys < 90 || dia < 60 ->
                "Low blood pressure" to
                    "On the low side. If you're feeling dizzy, lightheaded, or faint, sit or lie down " +
                    "and rest. Occasional low readings are common and not usually concerning on their own."
            else ->
                "Normal" to "Right in the healthy range."
        }

        val ppNote = when {
            pulsePressure < 25 ->
                " Your pulse pressure is on the narrow side, which can sometimes reflect reduced " +
                    "stroke volume — worth mentioning if it's a repeated pattern."
            pulsePressure >= 100 ->
                " Your pulse pressure is quite wide, which is linked to stiffer arteries and higher " +
                    "cardiovascular risk over time — worth discussing with a doctor if this persists."
            pulsePressure >= 60 ->
                " Your pulse pressure is a little wide — not alarming on its own, but worth watching."
            else -> ""
        }

        val pulseNote = when {
            pulse > 100 -> " Your pulse was also on the high side."
            pulse < 60 -> " Your pulse was also on the low side (normal if you're generally fit/active)."
            else -> ""
        }

        return bpNote.first to (bpNote.second + ppNote + pulseNote)
    }
}
