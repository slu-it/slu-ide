package dev.sluide.fileviewers

/**
 * The services of one window that file views look up by their exact type through [FileViewHost.windowService]
 * (ADR 0070), e.g. the window's HTTP client session or background tasks: window-scoped beans that views of singleton
 * viewers cannot get injected. Register only services that views of another module need. Used on the FX thread.
 */
class WindowServices {
    private val services = mutableMapOf<Class<*>, Any>()

    /** Registers [service] under exactly [type]; a type can be registered once. */
    fun <T : Any> register(
        type: Class<T>,
        service: T,
    ) {
        require(type !in services) { "${type.name} is already registered" }
        services[type] = service
    }

    /** The service registered under exactly [type], or `null`; subtypes and supertypes do not match. */
    fun <T : Any> get(type: Class<T>): T? = services[type]?.let(type::cast)
}
