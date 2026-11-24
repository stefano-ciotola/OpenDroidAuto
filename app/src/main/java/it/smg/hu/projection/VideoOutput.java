package it.smg.hu.projection;

import android.view.SurfaceView;

import it.smg.hu.config.Settings;
import it.smg.libs.common.Log;

public abstract class VideoOutput extends it.smg.libs.aasdk.projection.VideoOutput {

    protected final Settings settings_;

    protected SurfaceView surfaceView_;

    /** The output of the running session, for the statistics overlay. */
    private static volatile VideoOutput active_;

    /**
     * Statistics of the running video output, or null when there is none or it
     * does not collect any. Cheap and non-blocking, safe on the UI thread.
     */
    public static String activeStatsReport() {
        VideoOutput output = active_;
        return output == null ? null : output.statsReport();
    }

    protected static void setActive(VideoOutput output) {
        active_ = output;
    }

    protected static void clearActive(VideoOutput output) {
        if (active_ == output) {
            active_ = null;
        }
    }

    protected String statsReport() {
        return null;
    }

    public VideoOutput(SurfaceView surfaceView){
        surfaceView_ = surfaceView;

        settings_ = Settings.instance();

        fps_ = fpsFromSettings(settings_.video.fps());
        resolution_ = settings_.video.resolution();
        dpi_ = settings_.video.dpi();

        String margin = settings_.video.margin();
        try {
            if (margin != null && !margin.trim().isEmpty()) {
                String[] values = margin.split(",");
                if (values.length == 2) {
                    margin_ = new Rect(Integer.parseInt(values[0]), Integer.parseInt(values[1]));
                }
            }
        } catch (Exception e){
            Log.e(tag(), "error on load margin (" + margin + ")", e);
            margin_ = new Rect(0,0);
        }
    }

    protected int fpsFromSettings(int fps){
        switch (fps){
            case 2:
                return 60;
            case 1:
            default:
                return 30;
        }
    }

//    @Override
//    public int getVideoFPS() {
//        return fps_;
//    }
//
//    @Override
//    public int getVideoResolution() {
//        return resolution_;
//    }
//
//    @Override
//    public int getScreenDPI() {
//        return dpi_;
//    }
//
//    @Override
//    public Rect getVideoMargins() {
//        return margin_;
//    }

    protected abstract String tag();
}
