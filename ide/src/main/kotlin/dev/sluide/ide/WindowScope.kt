package dev.sluide.ide

import org.springframework.beans.factory.ObjectFactory
import org.springframework.beans.factory.config.CustomScopeConfigurer
import org.springframework.beans.factory.config.Scope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The scope of beans that every project window has its own instance of (ADR 0014). */
const val WINDOW_SCOPE = "window"

/** The window-scoped beans of one window and the callbacks that destroy them (e.g. `close()`). */
class WindowBeans {
    internal val beans = LinkedHashMap<String, Any>()
    internal val destructionCallbacks = LinkedHashMap<String, Runnable>()
}

/**
 * Spring scope "window": beans annotated `@Scope("window")` exist once per [WindowBeans]. They can only be created
 * inside [inWindow]; singletons must not depend on them. [destroy] runs their destruction callbacks, newest first.
 * Used on the JavaFX thread only.
 */
class WindowScope : Scope {
    private var current: WindowBeans? = null

    /** Runs [block] with [beans] as the window whose beans are looked up and created. */
    fun <T> inWindow(
        beans: WindowBeans,
        block: () -> T,
    ): T {
        val previous = current
        current = beans
        try {
            return block()
        } finally {
            current = previous
        }
    }

    /** Destroys the beans of a window that has been closed. */
    fun destroy(beans: WindowBeans) {
        val callbacks = beans.destructionCallbacks.values.reversed()
        beans.destructionCallbacks.clear()
        beans.beans.clear()
        callbacks.forEach(Runnable::run)
    }

    private fun window(name: String) =
        checkNotNull(current) { "The window-scoped bean '$name' can only be used inside a window" }

    override fun get(
        name: String,
        objectFactory: ObjectFactory<*>,
    ): Any {
        val window = window(name)
        window.beans[name]?.let { return it }
        // Not getOrPut: creating a bean may create the beans it depends on, which changes the map.
        val bean = objectFactory.getObject()
        window.beans[name] = bean
        return bean
    }

    override fun remove(name: String): Any? {
        val window = current ?: return null
        window.destructionCallbacks.remove(name)
        return window.beans.remove(name)
    }

    override fun registerDestructionCallback(
        name: String,
        callback: Runnable,
    ) {
        window(name).destructionCallbacks[name] = callback
    }

    override fun resolveContextualObject(key: String): Any? = null

    override fun getConversationId(): String? = null
}

/** Registers a [WindowScope]; find it with `beanFactory.getRegisteredScope(WINDOW_SCOPE)`. */
@Configuration(proxyBeanMethods = false)
class WindowScopeConfiguration {
    companion object {
        // Static: a bean factory post-processor must be created before all other beans.
        @JvmStatic
        @Bean
        fun windowScopeConfigurer() = CustomScopeConfigurer().apply { addScope(WINDOW_SCOPE, WindowScope()) }
    }
}
