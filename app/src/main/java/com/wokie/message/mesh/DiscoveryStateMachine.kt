package com.wokie.message.mesh

enum class ConnectionState {
    IDLE,
    DISCOVERING,
    PEER_FOUND,
    CONNECTION_REQUESTED,
    INCOMING_REQUEST,
    CONNECTION_ACCEPTED,
    CONNECTING,
    CONNECTED,
    SESSION_ESTABLISHING,
    FINGERPRINT_VERIFICATION,
    CHAT_READY,
    DISCONNECTED,
    ERROR
}

class DiscoveryStateMachine {
    var currentState: ConnectionState = ConnectionState.IDLE
        private set

    private var stateChangeListener: ((ConnectionState) -> Unit)? = null

    fun setStateChangeListener(listener: (ConnectionState) -> Unit) {
        stateChangeListener = listener
    }

    fun transitionTo(newState: ConnectionState) {
        if (isValidTransition(currentState, newState)) {
            currentState = newState
            stateChangeListener?.invoke(currentState)
        } else {
            // Reject invalid transitions, but allow forcing to ERROR or IDLE
            if (newState == ConnectionState.ERROR || newState == ConnectionState.IDLE || newState == ConnectionState.DISCONNECTED) {
                currentState = newState
                stateChangeListener?.invoke(currentState)
            }
        }
    }

    private fun isValidTransition(from: ConnectionState, to: ConnectionState): Boolean {
        return when (from) {
            ConnectionState.IDLE -> to == ConnectionState.DISCOVERING || to == ConnectionState.CONNECTING
            ConnectionState.DISCOVERING -> to == ConnectionState.PEER_FOUND || to == ConnectionState.IDLE
            ConnectionState.PEER_FOUND -> to == ConnectionState.CONNECTION_REQUESTED || to == ConnectionState.INCOMING_REQUEST || to == ConnectionState.DISCOVERING
            ConnectionState.CONNECTION_REQUESTED -> to == ConnectionState.CONNECTING || to == ConnectionState.DISCOVERING
            ConnectionState.INCOMING_REQUEST -> to == ConnectionState.CONNECTION_ACCEPTED || to == ConnectionState.DISCOVERING
            ConnectionState.CONNECTION_ACCEPTED -> to == ConnectionState.CONNECTING
            ConnectionState.CONNECTING -> to == ConnectionState.CONNECTED
            ConnectionState.CONNECTED -> to == ConnectionState.SESSION_ESTABLISHING
            ConnectionState.SESSION_ESTABLISHING -> to == ConnectionState.FINGERPRINT_VERIFICATION
            ConnectionState.FINGERPRINT_VERIFICATION -> to == ConnectionState.CHAT_READY
            ConnectionState.CHAT_READY -> to == ConnectionState.DISCONNECTED
            ConnectionState.DISCONNECTED -> to == ConnectionState.IDLE
            ConnectionState.ERROR -> to == ConnectionState.IDLE
        }
    }
}
