package hu.akarnokd.kotlin.flow

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import java.util.concurrent.atomic.AtomicReference

/**
 * Multicasts items to any number of collectors when they are ready to receive.
 *
 * @param <T> the element type of the [Flow]
 */
@FlowPreview
class PublishSubject<T> : AbstractFlow<T>(), SubjectAPI<T>  {

    companion object {
        private val EMPTY = arrayOf<InnerCollector<Any>>()
        private val TERMINATED = arrayOf<InnerCollector<Any>>()
    }

    @Suppress("UNCHECKED_CAST")
    private val collectors = AtomicReference(EMPTY as Array<InnerCollector<T>>)

    private var error : Throwable? = null

    /**
     * Returns true if this PublishSubject has any collectors.
     */
    override fun hasCollectors() : Boolean = collectors.get().isNotEmpty()

    /**
     * Returns the current number of collectors.
     */
    override fun collectorCount() : Int = collectors.get().size

    /**
     * Emit the value to all current collectors, waiting for each of them
     * to be ready for consuming it.
     */
    override suspend fun emit(value: T) {
        for (collector in collectors.get()) {
            collector.next(value)
        }
    }

    /**
     * Throw an error on the consumer side.
     */
    override suspend fun emitError(error: Throwable) {
        if (this.error == null) {
            this.error = error
            @Suppress("UNCHECKED_CAST")
            for (collector in collectors.getAndSet(TERMINATED as Array<InnerCollector<T>>)) {
                collector.error(error)
            }
        }
    }

    /**
     * Indicate no further items will be emitted
     */
    override suspend fun complete() {
        @Suppress("UNCHECKED_CAST")
        for (collector in collectors.getAndSet(TERMINATED as Array<InnerCollector<T>>)) {
            collector.complete()
        }
    }

    @Suppress("UNCHECKED_CAST", "")
    private fun add(inner: InnerCollector<T>) : Boolean {
        while (true) {

            val a = collectors.get()
            if (a as Any == TERMINATED as Any) {
                return false
            }
            val n = a.size
            val b = a.copyOf(n + 1)
            b[n] = inner
            if (collectors.compareAndSet(a, b as Array<InnerCollector<T>>)) {
                return true
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun remove(inner: InnerCollector<T>) {
        while (true) {
            val a = collectors.get()
            val n = a.size
            if (n == 0) {
                return
            }

            val j = a.indexOf(inner)
            if (j < 0) {
                return
            }

            var b = EMPTY as Array<InnerCollector<T>?>
            if (n != 1) {
                b = Array(n - 1) { null }
                System.arraycopy(a, 0, b, 0, j)
                System.arraycopy(a, j + 1, b, j, n - j - 1)
            }
            if (collectors.compareAndSet(a, b as Array<InnerCollector<T>>)) {
                return
            }
        }
    }

    /**
     * Start collecting signals from this PublishSubject.
     */
    @FlowPreview
    override suspend fun collectSafely(collector: FlowCollector<T>) {
        val inner = InnerCollector<T>()
        if (add(inner)) {
            while (true) {

                inner.readyConsumer()

                inner.awaitSignal()

                if (inner.hasValue) {
                    val v = inner.value!!
                    inner.value = null
                    inner.hasValue = false

                    try {
                        collector.emit(v)
                    } catch (exc: Throwable) {
                        remove(inner)

                        inner.readyConsumer() // unblock waiters
                        throw exc
                    }
                }

                if (inner.done) {
                    val ex = inner.error;
                    if (ex != null) {
                        throw ex
                    }
                    return
                }
            }
        }

        val ex = error
        if (ex != null) {
            throw ex
        }
    }

    private class InnerCollector<T> : Resumable() {
        var value: T? = null
        var error: Throwable? = null
        var done: Boolean = false
        var hasValue: Boolean = false

        val consumerReady = Resumable()

        suspend fun next(value : T) {
            consumerReady.await()

            this.value = value
            this.hasValue = true

            resume()
        }

        suspend fun error(error: Throwable) {
            consumerReady.await()

            this.error = error
            this.done = true

            resume()
        }

        suspend fun complete() {
            consumerReady.await()

            this.done = true

            resume()
        }

        suspend fun awaitSignal() {
            await()
        }

        fun readyConsumer() {
            consumerReady.resume()
        }
    }
}