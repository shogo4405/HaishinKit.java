package com.haishinkit.studio

data class Preference(var rtmpURL: String, var streamName: String) {
    companion object {
        var shared = Preference("rtmp://192.168.1.2:1935/live", "live")
    }
}
