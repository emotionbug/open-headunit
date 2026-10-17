package com.andrerinas.openheadunit.decoder.video

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class VideoParameterSetOwnershipTest {
    @Test fun `decoder retains normalized AVC and HEVC configuration beyond frame reuse`() =
        org.mockito.Mockito.mockStatic(SystemClock::class.java).use {
            val context = mock<Context>()
            val prefs = mock<SharedPreferences>()
            whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
            whenever(prefs.getInt(any(), any())).thenAnswer { it.arguments[1] }
            for (type in listOf(VideoDecoder.CodecType.H264, VideoDecoder.CodecType.H265)) {
                val decoder = VideoDecoder(Settings(context))
                val headers = if (type == VideoDecoder.CodecType.H264) listOf(7, 8) else listOf(64, 66, 68)
                val names = if (type == VideoDecoder.CodecType.H264) listOf("sps", "pps") else listOf("vps", "sps", "pps")
                val prefix = byteArrayOf(0, 0, 1)
                val config = headers.flatMap { (prefix + byteArrayOf(it.toByte(), 12)).toList() }.toByteArray()
                val picture = prefix + byteArrayOf(if (type == VideoDecoder.CodecType.H264) 0x65 else 38) + ByteArray(65536) { 85 }
                val payload = config + picture + prefix // Empty trailing NAL must not index beyond its header.
                val frame = byteArrayOf(99, 99) + payload + byteArrayOf(99)
                val method = VideoDecoder::class.java.getDeclaredMethod("scanAndApplyConfig",
                    ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    VideoDecoder.CodecType::class.java).apply { isAccessible = true }
                method.invoke(decoder, frame, 2, payload.size, type)
                frame.fill(99)
                names.forEachIndexed { index, name ->
                    val stored = VideoDecoder::class.java.getDeclaredField(name).apply { isAccessible = true }.get(decoder) as ByteArray
                    assertArrayEquals(byteArrayOf(0, 0, 0, 1, headers[index].toByte(), 12), stored)
                }
            }
        }
}
