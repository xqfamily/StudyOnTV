package com.studyontv.tv

import org.json.JSONObject

interface TunnelListener {
    fun onConnected(transportName: String)
    fun onMessage(envelope: JSONObject, transportName: String)
    fun onDisconnected(transportName: String, reason: String)
}

interface Transport {
    val name: String
    fun start()
    fun send(envelope: JSONObject): Boolean
    fun stop()
}
