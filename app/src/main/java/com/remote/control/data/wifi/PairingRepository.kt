package com.remote.control.data.wifi

interface PairingRepository {
    fun startPairing(ip: String)
    fun confirmPin(pin: String)
    fun getKeyPair(): java.security.KeyPair
}

class PairingRepositoryImpl : PairingRepository {
    override fun startPairing(ip: String) {
        // TODO: Implement pairing logic
    }

    override fun confirmPin(pin: String) {
        // TODO: Implement PIN confirmation logic
    }

    override fun getKeyPair(): java.security.KeyPair {
        val keyGen = java.security.KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        return keyGen.generateKeyPair()
    }
}
