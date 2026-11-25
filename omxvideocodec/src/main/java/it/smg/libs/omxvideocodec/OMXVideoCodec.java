package it.smg.libs.omxvideocodec;

import android.view.Surface;

import androidx.annotation.Keep;

import java.nio.ByteBuffer;

import it.smg.libs.common.Log;

public class OMXVideoCodec {

    private static final String TAG = "OMXVideoCodec";

    // Decoder options, a bit mask shared with DecoderOptions.h on the native side.
    /** Hand the decoder one input buffer at a time (lower display latency). */
    public static final int OPTION_ONE_INPUT_BUFFER = 1;
    /** Enable the Nvidia low-latency decoder parameters when the decoder has them. */
    public static final int OPTION_NVIDIA_LOW_LATENCY = 1 << 1;
    /** Previous behaviour: flush the backlog after 8 frames / 250 ms and wait for an IDR. */
    public static final int OPTION_LEGACY_DROP = 1 << 2;
    /** Collect per-stage statistics, see {@link #statsReport()}. */
    public static final int OPTION_STATS = 1 << 3;
    /** Rewrite the stream's SPS so the decoder shows each frame as soon as it is decoded. */
    public static final int OPTION_SPS_LOW_LATENCY = 1 << 4;

    private static native void nativeInit();
    private native long nativeSetup();
    private native void nativeSurfaceInit(Object surface, int width, int height);
    private native boolean nativeDecoderInit(int fps, int options);
    private native void nativeDelete();
    private native void nativeConsume(ByteBuffer buf, int len, long t);
    private native void nativeSetSps(ByteBuffer buf, int len);
    private static native String nativeStatsReport();

    @Keep
    private long handle_ = 0;

    /** Guards handle_ against decode racing with teardown. */
    private final Object codecLock_ = new Object();

    private Surface surfaceView_;
    private int width_;
    private int height_;
    private final int fps_;
    private final int options_;

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("common");
        System.loadLibrary("omxvideocodec-jni");

        nativeInit();
    }

    public OMXVideoCodec(int fps, int options){
        handle_ = nativeSetup();
        fps_ = fps;
        options_ = options;
    }

    public void setSurface(Surface surface, int width, int height){
        synchronized (codecLock_) {
            if (handle_ == 0) {
                return;
            }
            surfaceView_ = surface;
            width_ = width;
            height_ = height;
            nativeSurfaceInit(surface, width_, height_);
        }
    }

    public boolean init() {
        synchronized (codecLock_) {
            if (handle_ == 0) {
                return false;
            }
            return nativeDecoderInit(fps_, options_);
        }
    }

    /**
     * Latest statistics report, a few lines of plain text refreshed every two
     * seconds, or null when statistics are off or no window has closed yet.
     * Never blocks on the decoder.
     */
    public static String statsReport() {
        return nativeStatsReport();
    }

    public void shutdown() {
        // Serialised against mediaDecode: frames keep arriving on a native
        // io_service thread while the session is being torn down, and feeding one
        // into a decoder whose native side has just been freed is a use-after-free.
        synchronized (codecLock_) {
            if (handle_ == 0) {
                return;
            }
            nativeDelete();
            if (Log.isVerbose()) Log.v(TAG, "Native deleted");
            handle_ = 0;
        }
    }

    public void mediaDecode(long timestamp, ByteBuffer buf, int len) {
        if (Log.isVerbose()) Log.v(TAG, "mediaDecode");
        synchronized (codecLock_) {
            if (handle_ == 0) {
                return;
            }
            if (isSps(buf)) {
                nativeSetSps(buf, len);
            }
            nativeConsume(buf, len, timestamp);
        }
    }

    private boolean isSps(ByteBuffer buf) {
        // 4-byte Annex B start code plus the NAL header byte. A runt frame used to
        // throw IndexOutOfBounds straight into the native caller, killing the
        // io_service thread that delivered it.
        if (buf.limit() < 5) {
            return false;
        }
        return (buf.get(4) & 0x1f) == 7;
    }
}
