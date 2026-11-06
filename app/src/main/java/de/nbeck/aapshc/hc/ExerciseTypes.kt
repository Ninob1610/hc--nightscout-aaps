package de.nbeck.aapshc.hc

import androidx.health.connect.client.records.ExerciseSessionRecord as E

/** Deutsche Namen für die gängigsten Health-Connect-Trainingsarten. */
object ExerciseTypes {
    private val names = mapOf(
        E.EXERCISE_TYPE_RUNNING to "Laufen",
        E.EXERCISE_TYPE_RUNNING_TREADMILL to "Laufband",
        E.EXERCISE_TYPE_WALKING to "Gehen",
        E.EXERCISE_TYPE_HIKING to "Wandern",
        E.EXERCISE_TYPE_BIKING to "Radfahren",
        E.EXERCISE_TYPE_BIKING_STATIONARY to "Indoor-Radfahren",
        E.EXERCISE_TYPE_SWIMMING_POOL to "Schwimmen (Becken)",
        E.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Schwimmen (Freiwasser)",
        E.EXERCISE_TYPE_STRENGTH_TRAINING to "Krafttraining",
        E.EXERCISE_TYPE_WEIGHTLIFTING to "Gewichtheben",
        E.EXERCISE_TYPE_CALISTHENICS to "Calisthenics",
        E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        E.EXERCISE_TYPE_YOGA to "Yoga",
        E.EXERCISE_TYPE_PILATES to "Pilates",
        E.EXERCISE_TYPE_ROWING_MACHINE to "Rudergerät",
        E.EXERCISE_TYPE_ROWING to "Rudern",
        E.EXERCISE_TYPE_ELLIPTICAL to "Crosstrainer",
        E.EXERCISE_TYPE_STAIR_CLIMBING to "Treppensteigen",
        E.EXERCISE_TYPE_DANCING to "Tanzen",
        E.EXERCISE_TYPE_SOCCER to "Fußball",
        E.EXERCISE_TYPE_BASKETBALL to "Basketball",
        E.EXERCISE_TYPE_VOLLEYBALL to "Volleyball",
        E.EXERCISE_TYPE_TENNIS to "Tennis",
        E.EXERCISE_TYPE_TABLE_TENNIS to "Tischtennis",
        E.EXERCISE_TYPE_BADMINTON to "Badminton",
        E.EXERCISE_TYPE_MARTIAL_ARTS to "Kampfsport",
        E.EXERCISE_TYPE_GOLF to "Golf",
        E.EXERCISE_TYPE_SKIING to "Ski",
        E.EXERCISE_TYPE_SNOWBOARDING to "Snowboard",
        E.EXERCISE_TYPE_EXERCISE_CLASS to "Kurs",
        E.EXERCISE_TYPE_OTHER_WORKOUT to "Training",
    )

    fun name(type: Int): String = names[type] ?: "Training"
}
