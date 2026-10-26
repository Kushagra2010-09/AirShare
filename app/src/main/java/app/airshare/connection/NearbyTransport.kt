package app.airshare.connection

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.Strategy

/**
 * Thin wrapper over Nearby Connections so the rest of the app doesn't depend on the library directly
 * (keeps discovery / connection / transfer replaceable).
 *
 * Strategy P2P_CLUSTER is required: one sender must be able to connect to SEVERAL receivers at once
 * (P2P_STAR only lets a discoverer connect to one advertiser). Trade-off: possibly lower peak throughput
 * than P2P_POINT_TO_POINT. Throughput is UNMEASURED; see README.
 */
class NearbyTransport(context: Context) {
    companion object { const val SERVICE_ID = "app.airshare.v1" }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    private fun ok(e: Exception, vararg okCodes: Int) = (e as? ApiException)?.statusCode in okCodes

    fun startAdvertising(endpointName: String, cb: ConnectionLifecycleCallback, onDone: (errorCode: Int?) -> Unit) {
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(endpointName, SERVICE_ID, cb, opts)
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { e ->
                if (ok(e, ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING)) onDone(null)
                else onDone((e as? ApiException)?.statusCode ?: -1)
            }
    }

    fun stopAdvertising() = runCatching { client.stopAdvertising() }

    fun startDiscovery(cb: EndpointDiscoveryCallback, onDone: (errorCode: Int?) -> Unit) {
        val opts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(SERVICE_ID, cb, opts)
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { e ->
                if (ok(e, ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING)) onDone(null)
                else onDone((e as? ApiException)?.statusCode ?: -1)
            }
    }

    fun stopDiscovery() = runCatching { client.stopDiscovery() }

    fun request(myName: String, endpointId: String, cb: ConnectionLifecycleCallback, onFail: (Int) -> Unit) {
        client.requestConnection(myName, endpointId, cb).addOnFailureListener { e ->
            if (!ok(e, ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT)) onFail((e as? ApiException)?.statusCode ?: -1)
        }
    }

    fun accept(endpointId: String, cb: PayloadCallback) { client.acceptConnection(endpointId, cb) }
    fun reject(endpointId: String) { client.rejectConnection(endpointId) }
    fun send(endpointId: String, payload: Payload) { client.sendPayload(endpointId, payload) }
    fun cancelPayload(id: Long) { client.cancelPayload(id) }
    fun disconnect(endpointId: String) { runCatching { client.disconnectFromEndpoint(endpointId) } }
    fun stopAll() { runCatching { client.stopAllEndpoints() } }
}
