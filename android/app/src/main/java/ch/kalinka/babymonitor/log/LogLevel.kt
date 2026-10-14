package ch.kalinka.babymonitor.log

/** The phone's log levels, lowest first, named the way the server's api/logs takes them. */
enum class LogLevel(val wireName: String) {
    Verbose("Verbose"),
    Debug("Debug"),
    Info("Info"),
    Warn("Warn"),
    Error("Error"),
    Fatal("Fatal");

    companion object {
        fun named(name: String?): LogLevel? = entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}
