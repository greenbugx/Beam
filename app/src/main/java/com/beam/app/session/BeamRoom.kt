package com.beam.app.session

data class BeamRoom(
    val id: String,
    val name: String,
    val code: String,
)

data class BeamPeer(
    val endpointId: String,
    val endpointName: String,
    val connected: Boolean = false,
)
