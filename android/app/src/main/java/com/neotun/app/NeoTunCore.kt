package com.neotun.app

object NeoTunCore {
    init { System.loadLibrary("neotun_core") }
    external fun nativeVersion(): String
}
