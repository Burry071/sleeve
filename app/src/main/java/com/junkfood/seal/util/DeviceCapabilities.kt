package com.junkfood.seal.util

import android.media.MediaCodecList
import android.os.Build
import com.junkfood.seal.util.PreferenceUtil.containsKey
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean

/** Hardware probes that are answered once per install, then cached in preferences. */
internal object DeviceCapabilities {

    fun av1HardwareAccelerated(): Boolean {
        if (PreferenceUtil.containsKey(AV1_HARDWARE_ACCELERATED)) {
            return AV1_HARDWARE_ACCELERATED.getBoolean()
        } else {
            val res =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    false
                } else {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                        info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } &&
                            info.isHardwareAccelerated
                    }
                }
            AV1_HARDWARE_ACCELERATED.updateBoolean(res)
            return res
        }
    }
}
