package com.aicode.partyhub.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.UUID

data class LanRoom(
    val id: String,
    val name: String,
    val address: String,
    val tcpPort: Int,
    val webPort: Int,
    val gameId: String,
    val players: Int
)

class LanDiscovery(
    context: Context,
    private val onFound: (LanRoom) -> Unit,
    private val onLost: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var closed = false
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var desired: NsdServiceInfo? = null
    private var registered = false
    private var unregistering = false
    private val services = mutableMapOf<String, NsdServiceInfo>()
    private val rooms = mutableMapOf<String, LanRoom>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun advertise(roomId: String, name: String, tcpPort: Int, webPort: Int, gameId: String) =
        advertise(roomId, name, tcpPort, webPort, gameId, 1)

    fun advertise(roomId: String, name: String, tcpPort: Int, webPort: Int, gameId: String, players: Int) {
        require(tcpPort in 1..65535)
        val attributes = lanAttributes(roomId, name, webPort, gameId, players)
        val info = NsdServiceInfo().apply {
            serviceName = "PartyGame-${UUID.randomUUID().toString().take(12)}"
            serviceType = SERVICE_TYPE
            port = tcpPort
            attributes.forEach { (key, value) -> setAttribute(key, value) }
        }
        synchronized(lock) {
            check(!closed) { "Discovery is closed" }
            desired = info
            if (registration == null) registerNext() else if (registered) unregister()
        }
    }

    private fun registerNext() {
        val info = desired ?: return
        desired = null
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = synchronized(lock) {
                registered = true
                if (closed || desired != null) unregister()
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = synchronized(lock) {
                registration = null
                registered = false
                if (!closed) { onError("nsd: registration $errorCode"); registerNext() }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = synchronized(lock) {
                registration = null
                registered = false
                unregistering = false
                if (!closed) registerNext()
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = synchronized(lock) {
                unregistering = false
                if (!closed) onError("nsd: unregister $errorCode")
            }
        }
        registration = callback
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback) }
        catch (e: RuntimeException) { registration = null; onError("nsd: ${e.message}") }
    }

    private fun unregister() {
        val callback = registration ?: return
        if (unregistering) return
        unregistering = true
        try { manager.unregisterService(callback) }
        catch (e: RuntimeException) {
            unregistering = false
            if (!closed) onError("nsd: ${e.message}")
        }
    }

    fun discover() = synchronized(lock) {
        check(!closed) { "Discovery is closed" }
        if (discovery != null) return@synchronized
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = synchronized(lock) {
                if (closed) runCatching { manager.stopServiceDiscovery(this) }
                Unit
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = synchronized(lock) {
                discovery = null
                if (!closed) onError("nsd: discover $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = synchronized(lock) {
                if (!closed) onError("nsd: stop $errorCode")
            }
            override fun onDiscoveryStopped(serviceType: String) = synchronized(lock) {
                discovery = null
                if (!closed) rooms.values.map { it.id }.distinct().forEach(onLost)
                services.clear(); rooms.clear(); pending.clear()
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = synchronized(lock) {
                if (closed || serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.') || services.size >= 64) return@synchronized
                val key = serviceInfo.serviceName
                if (services.put(key, serviceInfo) != null) pending.removeAll { it.serviceName == key }
                pending.addLast(serviceInfo)
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = synchronized(lock) {
                val key = serviceInfo.serviceName
                services.remove(key)
                pending.removeAll { it.serviceName == key }
                val room = rooms.remove(key)
                if (!closed && room != null && rooms.values.none { it.id == room.id }) onLost(room.id)
            }
        }
        discovery = callback
        try { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback) }
        catch (e: RuntimeException) { discovery = null; onError("nsd: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (closed || resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        resolving = true
        try {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = synchronized(lock) {
                    resolving = false
                    if (!closed) onError("nsd: resolve $errorCode")
                    resolveNext()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = synchronized(lock) {
                    resolving = false
                    if (!closed && services[info.serviceName] === info) {
                        val address = serviceInfo.host?.hostAddress
                        val room = address?.let { parseLanRoom(it, serviceInfo.port, serviceInfo.attributes) }
                        if (room != null) {
                            rooms[info.serviceName] = room
                            onFound(room)
                        }
                    }
                    resolveNext()
                }
            })
        } catch (e: RuntimeException) {
            resolving = false
            if (!closed) onError("nsd: ${e.message}")
            resolveNext()
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        desired = null
        if (registered) unregister()
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        pending.clear(); services.clear(); rooms.clear()
    }

    companion object { const val SERVICE_TYPE = "_partyhub._tcp." }
}

internal fun lanAttributes(roomId: String, name: String, webPort: Int, gameId: String, players: Int): Map<String, String> {
    require(roomId.isNotBlank() && encodeUtf8(roomId, 80).isNotEmpty())
    require(name.isNotBlank() && encodeUtf8(name, 120).isNotEmpty())
    require(gameId.isNotBlank() && encodeUtf8(gameId, 64).isNotEmpty())
    require(webPort in 0..65535 && players in 0..8)
    return mapOf("v" to "1", "id" to roomId, "name" to name, "web" to "$webPort", "game" to gameId, "players" to "$players")
}

internal fun parseLanRoom(address: String, tcpPort: Int, attributes: Map<String, ByteArray>): LanRoom? = try {
    require(tcpPort in 1..65535 && attributes.size <= 16)
    val fields = attributes.mapValues { (_, bytes) -> require(bytes.size <= 255); decodeUtf8(bytes) }
    require(fields["v"] == "1")
    val id = fields.getValue("id")
    val name = fields.getValue("name")
    val web = fields.getValue("web").toInt()
    val game = fields.getValue("game")
    val players = fields.getValue("players").toInt()
    lanAttributes(id, name, web, game, players)
    LanRoom(id, name, address, tcpPort, web, game, players)
} catch (_: Exception) { null }
