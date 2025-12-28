package com.ipcam

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helper class for writing MP4 boxes (atoms) for fragmented MP4 (fMP4) streaming.
 * Implements a minimal subset of ISO/IEC 14496-12 (MPEG-4 Part 12) for live streaming.
 */
object Mp4BoxWriter {
    
    // MP4 box flags
    private const val FTYP_MINOR_VERSION = 0x00000200
    private const val TRUN_FLAG_DATA_OFFSET_PRESENT = 0x000001
    private const val TRUN_FLAG_SAMPLE_SIZE_PRESENT = 0x000200
    private const val TRUN_FLAGS = TRUN_FLAG_DATA_OFFSET_PRESENT or TRUN_FLAG_SAMPLE_SIZE_PRESENT // 0x000301
    
    // Rate and volume constants (fixed-point)
    private const val FIXED_POINT_ONE = 0x00010000 // 1.0 in 16.16 fixed point
    private const val MATRIX_UNITY_W = 0x40000000 // 1.0 in 2.30 fixed point
    
    /**
     * Write a 32-bit big-endian integer
     */
    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
    
    /**
     * Write a 64-bit big-endian long
     */
    private fun ByteArrayOutputStream.writeInt64(value: Long) {
        write((value shr 56).toInt() and 0xFF)
        write((value shr 48).toInt() and 0xFF)
        write((value shr 40).toInt() and 0xFF)
        write((value shr 32).toInt() and 0xFF)
        write((value shr 24).toInt() and 0xFF)
        write((value shr 16).toInt() and 0xFF)
        write((value shr 8).toInt() and 0xFF)
        write(value.toInt() and 0xFF)
    }
    
    /**
     * Write a 4-character box type
     */
    private fun ByteArrayOutputStream.writeBoxType(type: String) {
        write(type.toByteArray(Charsets.US_ASCII))
    }
    
    /**
     * Create an ftyp (file type) box
     */
    fun createFtypBox(): ByteArray {
        val out = ByteArrayOutputStream()
        
        // Box size (will be calculated)
        val size = 28
        out.writeInt32(size)
        
        // Box type 'ftyp'
        out.writeBoxType("ftyp")
        
        // Major brand: 'isom' (ISO Base Media)
        out.writeBoxType("isom")
        
        // Minor version
        out.writeInt32(FTYP_MINOR_VERSION)
        
        // Compatible brands
        out.writeBoxType("isom")  // ISO Base Media
        out.writeBoxType("iso5")  // ISO Base Media version 5
        out.writeBoxType("dash")  // DASH
        
        return out.toByteArray()
    }
    
    /**
     * Create a simplified moov (movie) box for live streaming
     * Note: In production, this should include proper track information, codec config (SPS/PPS), etc.
     * For now, we use a minimal version that works with most players.
     */
    fun createMoovBox(width: Int, height: Int, codecConfig: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        
        // For simplicity, create a minimal moov box
        // A full implementation would include:
        // - mvhd (movie header)
        // - trak (track with tkhd, mdia with mdhd, hdlr, minf with stbl)
        // - mvex (movie extends for fragmented MP4)
        
        // Simplified version: just enough to signal it's H.264 video
        val moovContent = ByteArrayOutputStream()
        
        // mvhd box (simplified)
        val mvhdSize = 108
        moovContent.writeInt32(mvhdSize)
        moovContent.writeBoxType("mvhd")
        moovContent.writeInt32(0) // version and flags
        moovContent.writeInt32(0) // creation time
        moovContent.writeInt32(0) // modification time
        moovContent.writeInt32(30) // timescale (30 fps)
        moovContent.writeInt32(0) // duration (unknown for live stream)
        moovContent.writeInt32(FIXED_POINT_ONE) // rate (1.0 in 16.16 fixed point)
        moovContent.write(byteArrayOf(0x01, 0x00)) // volume (1.0 in 8.8 fixed point)
        moovContent.write(ByteArray(10)) // reserved
        // Unity matrix (identity transformation)
        moovContent.writeInt32(FIXED_POINT_ONE) // a
        moovContent.writeInt32(0) // b
        moovContent.writeInt32(0) // u
        moovContent.writeInt32(0) // c
        moovContent.writeInt32(FIXED_POINT_ONE) // d
        moovContent.writeInt32(0) // v
        moovContent.writeInt32(0) // x
        moovContent.writeInt32(0) // y
        moovContent.writeInt32(MATRIX_UNITY_W) // w (1.0 in 2.30 fixed point)
        moovContent.write(ByteArray(24)) // pre_defined
        moovContent.writeInt32(2) // next_track_ID
        
        // mvex box (movie extends)
        val mvexContent = ByteArrayOutputStream()
        
        // mehd box (movie extends header) - optional but helpful
        mvexContent.writeInt32(16)
        mvexContent.writeBoxType("mehd")
        mvexContent.writeInt32(0) // version and flags
        mvexContent.writeInt32(0) // fragment_duration (unknown)
        
        // trex box (track extends)
        mvexContent.writeInt32(32)
        mvexContent.writeBoxType("trex")
        mvexContent.writeInt32(0) // version and flags
        mvexContent.writeInt32(1) // track_ID
        mvexContent.writeInt32(1) // default_sample_description_index
        mvexContent.writeInt32(0) // default_sample_duration
        mvexContent.writeInt32(0) // default_sample_size
        mvexContent.writeInt32(0) // default_sample_flags
        
        val mvexBytes = mvexContent.toByteArray()
        moovContent.writeInt32(8 + mvexBytes.size)
        moovContent.writeBoxType("mvex")
        moovContent.write(mvexBytes)
        
        // Write final moov box
        val moovBytes = moovContent.toByteArray()
        out.writeInt32(8 + moovBytes.size)
        out.writeBoxType("moov")
        out.write(moovBytes)
        
        return out.toByteArray()
    }
    
    /**
     * Create an moof (movie fragment) box
     */
    fun createMoofBox(sequenceNumber: Int, baseMediaDecodeTime: Long, sampleSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        
        val moofContent = ByteArrayOutputStream()
        
        // mfhd (movie fragment header)
        moofContent.writeInt32(16)
        moofContent.writeBoxType("mfhd")
        moofContent.writeInt32(0) // version and flags
        moofContent.writeInt32(sequenceNumber)
        
        // traf (track fragment)
        val trafContent = ByteArrayOutputStream()
        
        // tfhd (track fragment header)
        trafContent.writeInt32(16)
        trafContent.writeBoxType("tfhd")
        trafContent.writeInt32(0x020000) // flags: default-base-is-moof
        trafContent.writeInt32(1) // track_ID
        
        // tfdt (track fragment decode time)
        trafContent.writeInt32(20)
        trafContent.writeBoxType("tfdt")
        trafContent.writeInt32(0x01000000) // version 1
        trafContent.writeInt64(baseMediaDecodeTime)
        
        // trun (track fragment run)
        trafContent.writeInt32(20)
        trafContent.writeBoxType("trun")
        trafContent.writeInt32(TRUN_FLAGS) // flags: data-offset-present, sample-size-present
        trafContent.writeInt32(1) // sample_count
        trafContent.writeInt32(8) // data_offset (points to mdat)
        trafContent.writeInt32(sampleSize)
        
        val trafBytes = trafContent.toByteArray()
        moofContent.writeInt32(8 + trafBytes.size)
        moofContent.writeBoxType("traf")
        moofContent.write(trafBytes)
        
        // Write final moof box
        val moofBytes = moofContent.toByteArray()
        out.writeInt32(8 + moofBytes.size)
        out.writeBoxType("moof")
        out.write(moofBytes)
        
        return out.toByteArray()
    }
    
    /**
     * Create an mdat (media data) box with the actual frame data
     */
    fun createMdatBox(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        
        out.writeInt32(8 + data.size)
        out.writeBoxType("mdat")
        out.write(data)
        
        return out.toByteArray()
    }
}
