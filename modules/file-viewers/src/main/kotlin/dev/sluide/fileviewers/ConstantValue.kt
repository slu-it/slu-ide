package dev.sluide.fileviewers

import javafx.beans.InvalidationListener
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableValue

/**
 * An [ObservableValue] that never changes, for a default that all implementations of an interface share (e.g.
 * [FileView.activity]). It keeps no listeners, since it never tells them anything: a shared property that kept them
 * would keep everything they capture (a tab header, a window) alive for as long as the app runs.
 */
class ConstantValue<T>(
    private val value: T,
) : ObservableValue<T> {
    override fun getValue(): T = value

    override fun addListener(listener: ChangeListener<in T>?) {}

    override fun removeListener(listener: ChangeListener<in T>?) {}

    override fun addListener(listener: InvalidationListener?) {}

    override fun removeListener(listener: InvalidationListener?) {}
}

/** A [ConstantValue] of a [Boolean], for defaults typed as [ObservableBooleanValue]; it keeps no listeners either. */
class ConstantBoolean(
    private val value: Boolean,
) : ObservableBooleanValue {
    override fun get(): Boolean = value

    override fun getValue(): Boolean = value

    override fun addListener(listener: ChangeListener<in Boolean>?) {}

    override fun removeListener(listener: ChangeListener<in Boolean>?) {}

    override fun addListener(listener: InvalidationListener?) {}

    override fun removeListener(listener: InvalidationListener?) {}
}
