package com.pinna.app.core.model

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun Track.toNetworkVisibleTrack(): Track = copy(localUri = publicMediaUriFor(id))

fun publicMediaUriFor(trackId: String): String =
    "pinna-media://track/${URLEncoder.encode(trackId, StandardCharsets.UTF_8.name())}"
