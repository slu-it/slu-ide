package dev.sluide.codeintelligence.binding

private val log = System.getLogger(ServedFeatures::class.java.name)

/**
 * The features that send requests to the server, attached only while the binding's document is
 * [served][DocumentBinding.served] (ADR 0160): attached in order when it is (or becomes) served, closed last attached
 * first when it flips to not served, attached again on the flip back. The one guard of the request features: none
 * checks the gate itself. Their context menu items and hover providers go with their handles. FX thread.
 */
internal class ServedFeatures(
    private val features: List<BindingFeature>,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val handles = mutableListOf<AutoCloseable>()
        var attached = false

        fun detach() {
            for (handle in handles.asReversed()) {
                try {
                    handle.close()
                } catch (e: Exception) {
                    log.log(
                        System.Logger.Level.ERROR,
                        "Could not close a code intelligence feature of ${binding.file}",
                        e,
                    )
                }
            }
            handles.clear()
            attached = false
        }

        fun sync(served: Boolean) {
            if (served == attached || binding.isClosed) return
            if (!served) return detach()
            attached = true
            for (feature in features) {
                try {
                    feature.attach(binding)?.let(handles::add)
                } catch (e: RuntimeException) {
                    log.log(
                        System.Logger.Level.ERROR,
                        "A code intelligence feature failed to attach to ${binding.file}",
                        e,
                    )
                }
            }
        }

        // Subscribing tells the current value at once: attaches now if the document is served already.
        val subscription = binding.served.subscribe { served -> sync(served) }
        return AutoCloseable {
            subscription.unsubscribe()
            detach()
        }
    }
}
