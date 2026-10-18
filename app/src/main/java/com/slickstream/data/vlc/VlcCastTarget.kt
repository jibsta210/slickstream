package com.slickstream.data.vlc

/** Native VLC Cast output: remux supported tracks, convert unsupported ones on the phone. */
data class VlcCastTarget(val address: String, val controlPort: Int, val httpPort: Int) {
    init {
        // Values enter VLC's sout grammar, so accept only numeric IPv4 addresses from CastDevice.
        require(address.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
            }
        })
        require(controlPort in 1..65535 && httpPort in 1..65535)
    }

    fun mediaOptions(): List<String> = listOf(
        // Always downmix audio before the receiver-specific video remux/conversion. A receiver
        // accepting the video does not prove its HDMI/audio path can play the original audio.
        ":sout=#transcode{acodec=mp4a,aenc=avcodec,ab=192,channels=2,samplerate=48000}:chromecast{ip=$address,port=$controlPort,http-port=$httpPort}",
        // This filter reports the receiver's clock and forwards pause/seek to the receiver.
        ":demux-filter=cc_demux",
        ":no-sout-keep",
    )
}
