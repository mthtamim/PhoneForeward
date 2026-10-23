package com.mthtamim.phonelink

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * v2.18 (WS4): the app's single MQTT connection, shared by the
 * mqtt_message trigger and the mqtt_publish action.
 *
 * Client: Eclipse Paho mqttv3 (pure Java, no native code, no
 * transitive dependencies). The connection lives here — not in a
 * second service — and LinkService drives its lifecycle through
 * sync()/stop() alongside the other transports. While the settings
 * toggle is on and the broker config is valid the client stays
 * connected (Paho automatic reconnect + a reconnect attempt on every
 * sync() call), and it (re)subscribes to the topic filters the
 * enabled rules actually use whenever the rule set changes.
 */
object MqttManager {

    /** Connection state shown on the settings broker card. */
    enum class State { OFF, CONNECTING, CONNECTED, ERROR }

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var state: State = State.OFF
    @Volatile private var stateDetail: String = ""
    @Volatile private var activeBroker: String = ""
    @Volatile private var appCtx: Context? = null
    private val lock = Any()

    fun state(): State = state
    fun stateDetail(): String = stateDetail
    fun isConnected(): Boolean =
        state == State.CONNECTED && (client?.isConnected == true)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("phonelink_mqtt", Context.MODE_PRIVATE)

    /** Stable per-install client id (brokers reject duplicate ids). */
    private fun clientId(ctx: Context): String {
        var id = prefs(ctx).getString("client_id", "") ?: ""
        if (id.isEmpty()) {
            id = "pl-" + java.util.UUID.randomUUID().toString().take(12)
            prefs(ctx).edit().putString("client_id", id).apply()
        }
        return id
    }

    /** Topic filters of all enabled rules that use mqtt_message. */
    private fun wantedFilters(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            for (rule in Automation.rules(ctx)) {
                if (!rule.optBoolean("enabled", true)) continue
                for (trig in Automation.ruleTriggers(rule)) {
                    if (trig.optString("type") != "mqtt_message") continue
                    val f = trig.optString("param")
                        .substringBefore("|").trim()
                    if (f.isNotEmpty() && f !in out) out.add(f)
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /**
     * Brings the connection in line with the current settings:
     * off/invalid config → disconnect; changed broker → reconnect;
     * same broker → just (re)subscribe. Cheap and safe to call from
     * anywhere (service start, settings save, rule saves).
     */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (!Config.mqttOn(app) || Config.mqttBroker(app).isBlank()) {
            disconnect()
            state = State.OFF
            stateDetail = ""
            return
        }
        val broker = Config.mqttBroker(app).trim()
        synchronized(lock) {
            val c = client
            if (c != null && activeBroker == broker) {
                if (c.isConnected) {
                    state = State.CONNECTED
                    subscribeAll(app, c)
                } else if (state != State.CONNECTING) {
                    connectLocked(app)
                }
                return
            }
        }
        disconnect()
        synchronized(lock) { connectLocked(app) }
    }

    private fun connectLocked(app: Context) {
        val broker = Config.mqttBroker(app).trim()
        if (!(broker.startsWith("tcp://") || broker.startsWith("ssl://"))) {
            state = State.ERROR
            stateDetail = "broker URL must start with tcp:// or ssl://"
            return
        }
        state = State.CONNECTING
        stateDetail = ""
        try {
            val c = MqttAsyncClient(broker, clientId(app), MemoryPersistence())
            client = c
            activeBroker = broker
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean,
                                             serverURI: String?) {
                    state = State.CONNECTED
                    stateDetail = ""
                    appCtx?.let { subscribeAll(it, c) }
                }
                override fun connectionLost(cause: Throwable?) {
                    if (state != State.OFF) state = State.CONNECTING
                    stateDetail = cause?.message ?: ""
                }
                override fun messageArrived(topic: String?,
                                            message: MqttMessage?) {
                    val ctx = appCtx ?: return
                    val payload = try {
                        String(message?.payload ?: ByteArray(0),
                            Charsets.UTF_8)
                    } catch (e: Exception) { "" }
                    Automation.onEvent(ctx, "mqtt_message", JSONObject()
                        .put("topic", topic ?: "")
                        .put("title", topic ?: "")
                        .put("body", payload)
                        .put("payload", payload)
                        .put("kind", "mqtt"))
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) { }
            })
            val opts = MqttConnectOptions()
            opts.isAutomaticReconnect = true
            opts.isCleanSession = true
            opts.connectionTimeout = 15
            opts.keepAliveInterval = 45
            opts.maxReconnectDelay = 60_000
            val user = Config.mqttUser(app)
            if (user.isNotEmpty()) {
                opts.userName = user
                opts.password = Config.mqttPass(app).toCharArray()
            }
            // Paho's automatic reconnect only covers drops AFTER a
            // successful first connect, so the first attempt reports
            // through this listener (failure → ERROR state; the next
            // sync() — service start, rule save, settings save —
            // retries).
            c.connect(opts, null, object :
                org.eclipse.paho.client.mqttv3.IMqttActionListener {
                override fun onSuccess(token:
                    org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    state = State.CONNECTED
                    stateDetail = ""
                }
                override fun onFailure(token:
                    org.eclipse.paho.client.mqttv3.IMqttToken?,
                    cause: Throwable?) {
                    if (state != State.OFF) state = State.ERROR
                    stateDetail = cause?.message
                        ?: cause?.javaClass?.simpleName ?: ""
                }
            })
        } catch (e: Exception) {
            state = State.ERROR
            stateDetail = e.message ?: e.javaClass.simpleName
        }
    }

    private fun subscribeAll(app: Context, c: MqttAsyncClient) {
        try {
            if (!c.isConnected) return
            val filters = wantedFilters(app)
            if (filters.isEmpty()) return
            c.subscribe(filters.toTypedArray(),
                IntArray(filters.size) { 1 })
        } catch (_: Exception) { }
    }

    /** Settings-save path: full reconnect so changed broker or
     *  credentials take effect immediately. */
    fun restart(ctx: Context) {
        stop()
        sync(ctx)
    }

    /** Called after rule saves: subscriptions follow the rule set on
     *  the EXISTING connection (no reconnect, no duplicate client). */
    fun refreshSubscriptions(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (!Config.mqttOn(app)) return
        val c = client ?: run { sync(app); return }
        if (c.isConnected) subscribeAll(app, c) else sync(app)
    }

    fun disconnect() {
        synchronized(lock) {
            try { client?.disconnectForcibly(500, 500) } catch (_: Exception) { }
            try { client?.close() } catch (_: Exception) { }
            client = null
            activeBroker = ""
        }
    }

    /** Service teardown: drop the connection and forget the state. */
    fun stop() {
        disconnect()
        state = State.OFF
        stateDetail = ""
    }

    /**
     * Publishes for the mqtt_publish action.
     * Param format: topic|payload|qos|retained — the trailing qos and
     * retained segments are optional and are only treated as flags
     * when both are exactly "0" or "1"; the payload itself may
     * contain '|' characters. Returns false when not connected or the
     * publish fails, so the action can log it.
     */
    fun publishParam(ctx: Context, param: String): Boolean {
        val parts = param.split("|")
        var topic = parts.getOrNull(0)?.trim() ?: ""
        var payload = ""
        var qos = 1
        var retained = false
        if (parts.size >= 4) {
            val q = parts[parts.size - 2].trim()
            val r = parts[parts.size - 1].trim()
            if ((q == "0" || q == "1") && (r == "0" || r == "1")) {
                topic = parts[0].trim()
                payload = parts.subList(1, parts.size - 2).joinToString("|")
                qos = q.toInt()
                retained = r == "1"
            } else {
                payload = parts.drop(1).joinToString("|")
            }
        } else if (parts.size >= 2) {
            payload = parts.drop(1).joinToString("|")
        }
        if (topic.isEmpty()) return false
        return publish(topic, payload, qos, retained)
    }

    fun publish(topic: String, payload: String, qos: Int,
                retained: Boolean): Boolean {
        val c = client ?: return false
        return try {
            if (!c.isConnected) return false
            val msg = MqttMessage(payload.toByteArray(Charsets.UTF_8))
            msg.qos = qos.coerceIn(0, 1)
            msg.isRetained = retained
            c.publish(topic, msg)
            true
        } catch (e: Exception) { false }
    }

    /** One-shot connection test for the settings card, on a throwaway
     *  client so the live connection is never disturbed. The callback
     *  fires on a background thread. */
    fun test(ctx: Context, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        val broker = Config.mqttBroker(app).trim()
        if (!(broker.startsWith("tcp://") || broker.startsWith("ssl://"))) {
            done(false, "broker URL must start with tcp:// or ssl://")
            return
        }
        Thread {
            var c: MqttAsyncClient? = null
            try {
                c = MqttAsyncClient(broker,
                    clientId(app) + "-test", MemoryPersistence())
                val opts = MqttConnectOptions()
                opts.connectionTimeout = 12
                opts.isCleanSession = true
                val user = Config.mqttUser(app)
                if (user.isNotEmpty()) {
                    opts.userName = user
                    opts.password = Config.mqttPass(app).toCharArray()
                }
                c.connect(opts).waitForCompletion(15_000)
                val ok = c.isConnected
                try { c.disconnect().waitForCompletion(3_000) }
                catch (_: Exception) { }
                done(ok, if (ok) "" else "not connected")
            } catch (e: Exception) {
                done(false, e.message ?: e.javaClass.simpleName)
            } finally {
                try { c?.close() } catch (_: Exception) { }
            }
        }.start()
    }
}
