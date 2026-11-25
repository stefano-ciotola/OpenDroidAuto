package it.smg.hu.projection;

import android.media.AudioFormat;
import android.media.AudioRecord;

import androidx.annotation.Keep;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.libs.common.Log;

public class AudioInput extends it.smg.libs.aasdk.projection.AudioInput {

    private static final String TAG = "AudioInput";

    private final Settings settings_;
    private final ReentrantReadWriteLock recordLock_ = new ReentrantReadWriteLock();

    private volatile AudioRecord audioRecord_;
    private volatile boolean isActive_;

    public AudioInput(){
        settings_ = Settings.instance();

        sampleSize_ = settings_.audio.micSampleSize();
        sourceType_ = settings_.audio.micSource();
        sampleRate_ = settings_.audio.micSampleRate();
        channelConfig_ = settings_.audio.micChannelCount();
    }

    private HondaConnectManager hondaManager() {
        return settings_.advanced.hondaIntegrationEnabled() ? HondaConnectManager.instance() : null;
    }

    @Keep
    @Override
    public boolean open() {
        bufferSize_ = AudioRecord.getMinBufferSize(sampleRate_, channels2num(channelConfig_), sampleSizeFromInt(sampleSize_));

        if (bufferSize_ <= 0) {
            Log.e(TAG, "Invalid AudioRecord min buffer size " + bufferSize_ + " sampleRate=" + sampleRate_ + " channelMask=" + channelConfig_ + " sampleSize=" + sampleSize_);
            return false;
        }

        return true;
    }

    public static int sampleSizeFromInt(int sampleSize){
        switch (sampleSize){
            case 16:
                return AudioFormat.ENCODING_PCM_16BIT;
            case 8:
                return AudioFormat.ENCODING_PCM_8BIT;
            default:
                return AudioFormat.ENCODING_INVALID;
        }
    }

    public static int channels2num(int channelCount) {
        switch (channelCount) {
            case 2:
                return AudioFormat.CHANNEL_IN_STEREO;
            case 1:
            default:
                return AudioFormat.CHANNEL_IN_MONO;
        }
    }

    @Keep
    @Override
    public boolean isActive() {
        return isActive_;
    }

    @Keep
    @Override
    public int read(ByteBuffer buffer) {
        int bytesToRead = buffer.remaining();
        if (bytesToRead <= 0) {
            return 0;
        }

        recordLock_.readLock().lock();
        try {
            AudioRecord record = audioRecord_;
            if (record == null || !isActive_) {
                return -1;
            }

            try {
                int read = record.read(buffer, bytesToRead);
                if (read < 0 || !isActive_) {
                    return -1;
                }
                return read;
            } catch (IllegalStateException e) {
                if (isActive_) {
                    Log.e(TAG, "AudioRecord read failed", e);
                }
                return -1;
            }
        } finally {
            recordLock_.readLock().unlock();
        }
    }

    @Keep
    @Override
    public boolean start() {
        if (audioRecord_ != null) {
            stop();
        }

        final AudioRecord record;
        try {
            record = new AudioRecord(sourceType_, sampleRate_, channels2num(channelConfig_), sampleSizeFromInt(sampleSize_), bufferSize_ * 4);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid AudioRecord configuration", e);
            return false;
        }

        HondaConnectManager hondaManager = hondaManager();
        if (hondaManager != null) {
            hondaManager.startMicSession();
        }

        if (Log.isDebug()) Log.d(TAG, "State: " + record.getState() + ", recording state: " + record.getRecordingState());
        if (record.getState() != AudioRecord.STATE_INITIALIZED){
            try {
                record.release();
            } catch (Throwable ignored) {}
            if (hondaManager != null) {
                hondaManager.stopMicSession();
            }
            stop();
            return false;
        }

        try {
            record.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to start microphone recording", e);
            try {
                record.release();
            } catch (Throwable ignored) {}
            if (hondaManager != null) {
                hondaManager.stopMicSession();
            }
            stop();
            return false;
        }

        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING ){
            Log.e(TAG, "wrong recording state " + record.getRecordingState());
            try {
                record.stop();
            } catch (IllegalStateException ignored) {}
            try {
                record.release();
            } catch (Throwable ignored) {}
            if (hondaManager != null) {
                hondaManager.stopMicSession();
            }
            stop();
            return false;
        }

        recordLock_.writeLock().lock();
        try {
            audioRecord_ = record;
            isActive_ = true;
        } finally {
            recordLock_.writeLock().unlock();
        }
        return true;
    }

    @Keep
    @Override
    public void stop() {
        if (Log.isInfo()) Log.i(TAG, "stop -> isActive= " + isActive_);

        boolean wasActive = isActive_;
        isActive_ = false;
        AudioRecord record = audioRecord_;

        if (record != null) {
            if (Log.isDebug()) Log.d(TAG, "stop audioRecord");
            try {
                record.stop();
            } catch (IllegalStateException e) {
                Log.e(TAG, "stop audioRecord error", e);
            }
        }

        // Release only after any in-flight read() exits so API 15 libmedia never frees native buffers mid-read.
        recordLock_.writeLock().lock();
        try {
            record = audioRecord_;
            audioRecord_ = null;
            if (record != null) {
                if (Log.isDebug()) Log.d(TAG, "release audioRecord");
                try {
                    record.release();
                } catch (Throwable e) {
                    Log.e(TAG, "release audioRecord error", e);
                }
            }
        } finally {
            recordLock_.writeLock().unlock();
        }

        if (wasActive) {
            HondaConnectManager hondaManager = hondaManager();
            if (hondaManager != null) {
                hondaManager.stopMicSession();
            }
        }

        if (Log.isDebug()) Log.d(TAG, "stop done");
    }

}
