package com.jagdeep.automation

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** Exactly one process-owned lease; failed acquisition never pretends the display is held. */
internal class DisplayAwakeLease(private val acquire: () -> AutoCloseable) {
    private var lease: AutoCloseable? = null
    @Synchronized fun setAwake(awake: Boolean) {
        if (awake) { if (lease == null) lease = acquire() }
        else { val old = lease; lease = null; old?.close() }
    }
}

internal data class IdleService(val destination: String, val path: String, val api: String, val release: String, val gnome: Boolean = false)
internal val idleServices = listOf(
    IdleService("org.gnome.SessionManager", "/org/gnome/SessionManager", "org.gnome.SessionManager", "Uninhibit", true),
    IdleService("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", "org.freedesktop.ScreenSaver", "UnInhibit"),
    IdleService("org.freedesktop.ScreenSaver", "/ScreenSaver", "org.freedesktop.ScreenSaver", "UnInhibit"),
)
internal interface IdleSession : AutoCloseable {
    fun inhibit(service: IdleService): Int?
    fun release(service: IdleService, cookie: Int)
}

/** Keep the D-Bus connection alive for the entire hold; one-shot command-line calls cannot do this. */
internal fun acquireLinuxDisplayInhibition(open: () -> IdleSession = { GioIdleSession() }): AutoCloseable {
    val connection = try { open() } catch (error: LinkageError) { throw IllegalStateException("Desktop inhibition libraries are unavailable", error) }
    try {
        for (service in idleServices) {
            val cookie = connection.inhibit(service) ?: continue
            val released = java.util.concurrent.atomic.AtomicBoolean()
            return AutoCloseable { if(released.compareAndSet(false,true)) { try { connection.release(service,cookie) } finally { connection.close() } } }
        }
        error("This desktop has no compatible idle-inhibition service")
    } catch (error: Throwable) { connection.close(); throw error }
}

/** GLib's synchronous GIO D-Bus API works on X11 and Wayland without an application window ID. */
private class GioIdleSession : IdleSession {
    private val gio = Native.load("gio-2.0", Gio::class.java)
    private val glib = Native.load("glib-2.0", Glib::class.java)
    private val objects = Native.load("gobject-2.0", GObjects::class.java)
    private val connection: Pointer
    init {
        val failure = PointerByReference()
        val value = gio.g_bus_get_sync(2,null,failure) // G_BUS_TYPE_SESSION
        failure.value?.let(glib::g_error_free)
        connection = value ?: error("The desktop session bus is unavailable")
    }
    private fun text(value: String) = glib.g_variant_new_string(value)
    private fun number(value: Int) = glib.g_variant_new_uint32(value)
    private fun tuple(vararg values: Pointer) = glib.g_variant_new_tuple(values,NativeLong(values.size.toLong()))
    private fun call(service: IdleService, method: String, parameters: Pointer): Pointer? {
        val error = PointerByReference()
        val reply = gio.g_dbus_connection_call_sync(connection,service.destination,service.path,service.api,method,parameters,null,0,3000,null,error)
        error.value?.let(glib::g_error_free)
        return reply
    }
    override fun inhibit(service: IdleService): Int? {
        val parameters = if(service.gnome) tuple(text("Desktop automation"),number(0),text("A routine is running"),number(12)) // idle + suspend
            else tuple(text("Desktop automation"),text("A routine is running"))
        val reply = call(service,"Inhibit",parameters) ?: return null
        return try {
            // Inhibit returns a single uint32 cookie. Validate before calling typed native getters.
            if(glib.g_variant_get_type_string(reply) != "(u)") return null
            val cookie = glib.g_variant_get_child_value(reply,NativeLong(0))
            try { glib.g_variant_get_uint32(cookie) } finally { glib.g_variant_unref(cookie) }
        } finally { glib.g_variant_unref(reply) }
    }
    override fun release(service: IdleService, cookie: Int) {
        call(service,service.release,tuple(number(cookie)))?.let(glib::g_variant_unref)
    }
    override fun close() { objects.g_object_unref(connection) }
}

private interface Gio : Library {
    fun g_bus_get_sync(type: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
    fun g_dbus_connection_call_sync(connection: Pointer, name: String, path: String, api: String, method: String,
        parameters: Pointer?, replyType: Pointer?, flags: Int, timeout: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
}
private interface Glib : Library {
    fun g_variant_new_string(value: String): Pointer
    fun g_variant_new_uint32(value: Int): Pointer
    fun g_variant_new_tuple(children: Array<out Pointer>, count: NativeLong): Pointer
    fun g_variant_get_child_value(value: Pointer, index: NativeLong): Pointer
    fun g_variant_get_uint32(value: Pointer): Int
    fun g_variant_get_type_string(value: Pointer): String
    fun g_variant_unref(value: Pointer)
    fun g_error_free(error: Pointer)
}
private interface GObjects : Library { fun g_object_unref(value: Pointer) }
