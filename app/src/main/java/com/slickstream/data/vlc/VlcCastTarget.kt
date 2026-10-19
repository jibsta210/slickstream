package com.slickstream.data.vlc

/** Audio is always normalized; only a verified compatible H.264 track may bypass video encoding. */
data class VlcCastTarget(
    val address: String,
    val controlPort: Int,
    val httpPort: Int,
    val transcodeVideo: Boolean = true,
) {
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
        // VLC's Chromecast output assumes HEVC is supported until the receiver rejects it. Some
        // receivers report PLAYING with only one track, so enforce compatibility before that output.
        ":sout=#transcode{" + (if (transcodeVideo)
            "vcodec=h264,venc=x264{preset=ultrafast,tune=zerolatency,profile=main,level=4.1,crf=23,vbv-maxrate=10000,vbv-bufsize=10000},maxwidth=1920,maxheight=1080,fps=24,"
            else "") +
            "acodec=mp4a,aenc=avcodec,ab=192,channels=2,samplerate=48000}:chromecast{ip=$address,port=$controlPort,http-port=$httpPort}",
        // This filter reports the receiver's clock and forwards pause/seek to the receiver.
        ":demux-filter=cc_demux",
        ":no-sout-keep",
    )
}
