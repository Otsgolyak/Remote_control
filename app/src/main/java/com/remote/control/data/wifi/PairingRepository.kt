package com.remote.control.data.wifi

interface PairingRepository {
    fun startPairing(ip: String)
    fun confirmPin(pin: String)
}

class PairingRepositoryImpl : PairingRepository {
    override fun startPairing(ip: String) {
        // TODO: Implement pairing logic
    }

    override fun confirmPin(pin: String) {
        // TODO: Implement PIN confirmation logic
    }
}
