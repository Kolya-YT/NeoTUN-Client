package com.neotun.app

object NeoTunCore {
    init { System.loadLibrary("neotun_core") }

    external fun nativeVersion(): String
    external fun nativeVlessConfig(uri: String): String
    external fun nativeVlessEngine(uri: String): String
}
