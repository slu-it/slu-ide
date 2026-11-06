package dev.sluide.games.sudoku

enum class Level(val label: String, val key: String) {
    EASY("Easy", "easy"),
    MEDIUM("Medium", "medium"),
    HARD("Hard", "hard"),
    EXPERT("Expert", "expert"),
    ;

    companion object {
        val DEFAULT = MEDIUM

        fun ofKey(key: String?): Level? = entries.firstOrNull { it.key == key }
    }
}
