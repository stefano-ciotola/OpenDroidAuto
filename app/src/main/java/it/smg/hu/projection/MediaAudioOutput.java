package it.smg.hu.projection;

import it.smg.hu.manager.HondaConnectManager;
import it.smg.libs.aasdk.messenger.ChannelId;
import it.smg.libs.common.Log;

public class MediaAudioOutput extends AudioOutput {

    private static final String TAG = "MediaAudioOutput";

    public MediaAudioOutput(){
        super();

        channelConfig_ = settings_.audio.mediaChannelCount();
        sampleSize_ = settings_.audio.mediaSampleSize();
        sampleRate_ = settings_.audio.mediaSampleRate();

        HondaConnectManager hondaManager = settings_.advanced.hondaIntegrationEnabled() ? HondaConnectManager.instance() : null;
        if (hondaManager != null){
            audioCodecStreamType_ = hondaManager.mediaAudioStream(ChannelId.MEDIA_AUDIO);
        }
    }

    private HondaConnectManager hondaManager() {
        if (!settings_.advanced.hondaIntegrationEnabled()) {
            return null;
        }
        return HondaConnectManager.instance();
    }

    @Override
    public void start() {
        HondaConnectManager hondaManager = hondaManager();
        if (hondaManager != null){
            if (Log.isDebug()) Log.d(TAG, "requestAudioFocus");
            hondaManager.requestAudioFocus();
        }

        super.start();
    }

    @Override
    public void stop() {
        HondaConnectManager hondaManager = hondaManager();
        if (hondaManager != null){
            if (Log.isDebug()) Log.d(TAG, "releaseAudioFocus");
            hondaManager.releaseAudioFocus();
        }

        super.stop();
    }

    @Override
    public void suspend() {
        HondaConnectManager hondaManager = hondaManager();
        if (hondaManager != null){
            if (Log.isDebug()) Log.d(TAG, "releaseAudioFocus");
            hondaManager.releaseAudioFocus();
        }

        super.suspend();
    }

    @Override
    public String tag() {
        return TAG;
    }
}
