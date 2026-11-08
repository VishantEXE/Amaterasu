package dev.questheld.captureprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

public final class ProbeActivity extends Activity {
    private static final int CONSENT = 1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        setContentView(new FramePattern());
        if (state == null) {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            startActivityForResult(manager.createScreenCaptureIntent(), CONSENT);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CONSENT || result != RESULT_OK || data == null) {
            finish();
            return;
        }
        Intent capture = new Intent(this, CaptureService.class)
                .putExtra("grant", data)
                .putExtra("fps", getIntent().getIntExtra("fps", 120))
                .putExtra("avc", getIntent().getBooleanExtra("avc", false))
                .putExtra("uncapped", getIntent().getBooleanExtra("uncapped", false));
        startForegroundService(capture);
    }

    private final class FramePattern extends View {
        private final Paint paint = new Paint();
        private int frame;

        FramePattern() {
            super(ProbeActivity.this);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(28, 45, 70));
            float cell = getWidth() / 32f;
            float top = getHeight() / 3f;
            float bottom = getHeight() * 2f / 3f;
            int id = ++frame & 0xffff;
            CaptureService.noteDraw(System.nanoTime());
            int marker = 0xa0000 | id;
            for (int i = 0; i < 20; i++) {
                paint.setColor((marker & (1 << (19 - i))) == 0 ? Color.BLACK : Color.WHITE);
                canvas.drawRect((6 + i) * cell, top, (7 + i) * cell, bottom, paint);
            }
            paint.setColor(Color.WHITE);
            paint.setTextSize(36);
            canvas.drawText("QuestHeld capture probe · frame " + id, cell * 6, top - 30, paint);
            postInvalidateOnAnimation();
        }
    }
}
