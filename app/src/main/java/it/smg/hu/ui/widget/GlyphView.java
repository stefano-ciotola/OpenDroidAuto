package it.smg.hu.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws the few glyphs the home screen needs (play, recover, USB, busy arc,
 * check) as paths, so they stay sharp at any density without vector drawable
 * support on Android 4.0. Glyphs with a disc draw it in the disc colour.
 */
public class GlyphView extends View {

    public static final int PLAY = 0;
    public static final int RECOVER = 1;
    public static final int USB = 2;
    public static final int BUSY = 3;
    public static final int CHECK = 4;

    private final Paint fill_ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke_ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path_ = new Path();
    private final RectF oval_ = new RectF();
    private int glyph_ = PLAY;
    private int discColor_ = 0;
    private int glyphColor_ = 0xFFFFFFFF;

    public GlyphView(Context context) {
        this(context, null);
    }

    public GlyphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill_.setStyle(Paint.Style.FILL);
        stroke_.setStyle(Paint.Style.STROKE);
        stroke_.setStrokeCap(Paint.Cap.ROUND);
        stroke_.setStrokeJoin(Paint.Join.ROUND);
    }

    /** discColor 0 draws the glyph alone. */
    public void setGlyph(int glyph, int discColor, int glyphColor) {
        glyph_ = glyph;
        discColor_ = discColor;
        glyphColor_ = glyphColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) {
            return;
        }
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float c = size / 2f;
        canvas.save();
        canvas.translate(left, top);

        if (discColor_ != 0) {
            fill_.setColor(discColor_);
            canvas.drawCircle(c, c, c, fill_);
        }
        // Glyph geometry is laid out on a 24-unit grid, like the design icons.
        float inner = discColor_ != 0 ? size * 0.46f : size;
        float u = inner / 24f;
        float ox = c - inner / 2f;
        float oy = c - inner / 2f;
        fill_.setColor(glyphColor_);
        stroke_.setColor(glyphColor_);
        stroke_.setStrokeWidth(Math.max(1.5f, u * (discColor_ != 0 ? 2.2f : 1.6f)));
        path_.reset();

        switch (glyph_) {
            case PLAY:
                path_.moveTo(ox + 7.5f * u, oy + 4.5f * u);
                path_.lineTo(ox + 20f * u, oy + 12f * u);
                path_.lineTo(ox + 7.5f * u, oy + 19.5f * u);
                path_.close();
                canvas.drawPath(path_, fill_);
                break;
            case RECOVER:
                oval_.set(ox + 4f * u, oy + 4f * u, ox + 20f * u, oy + 20f * u);
                canvas.drawArc(oval_, -45f, 300f, false, stroke_);
                path_.moveTo(ox + 20f * u, oy + 3.5f * u);
                path_.lineTo(ox + 20f * u, oy + 8.5f * u);
                path_.lineTo(ox + 15f * u, oy + 8.5f * u);
                canvas.drawPath(path_, stroke_);
                break;
            case USB:
                path_.moveTo(ox + 12f * u, oy + 3f * u);
                path_.lineTo(ox + 12f * u, oy + 15f * u);
                path_.moveTo(ox + 9f * u, oy + 6f * u);
                path_.lineTo(ox + 12f * u, oy + 3f * u);
                path_.lineTo(ox + 15f * u, oy + 6f * u);
                path_.moveTo(ox + 8f * u, oy + 10f * u);
                path_.lineTo(ox + 8f * u, oy + 12.5f * u);
                path_.lineTo(ox + 12f * u, oy + 15f * u);
                path_.lineTo(ox + 16f * u, oy + 12.5f * u);
                path_.lineTo(ox + 16f * u, oy + 10f * u);
                canvas.drawPath(path_, stroke_);
                canvas.drawCircle(ox + 12f * u, oy + 18f * u, 2.5f * u, stroke_);
                break;
            case BUSY:
                oval_.set(ox + 3f * u, oy + 3f * u, ox + 21f * u, oy + 21f * u);
                canvas.drawArc(oval_, -90f, 270f, false, stroke_);
                break;
            case CHECK:
            default:
                path_.moveTo(ox + 5f * u, oy + 12.5f * u);
                path_.lineTo(ox + 10f * u, oy + 17.5f * u);
                path_.lineTo(ox + 19f * u, oy + 7f * u);
                canvas.drawPath(path_, stroke_);
                break;
        }
        canvas.restore();
    }
}
