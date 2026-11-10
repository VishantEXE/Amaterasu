package dev.questheld.host;

import android.app.AlertDialog;
import android.content.Context;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

final class HostPanels {
    static final class Panel {
        final AlertDialog dialog;
        final TextView detail;

        Panel(Context context, String title, String content) {
            float density = context.getResources().getDisplayMetrics().density;
            int horizontal = Math.round(24 * density), vertical = Math.round(12 * density);
            detail = new TextView(context);
            detail.setPadding(horizontal, vertical, horizontal, vertical);
            detail.setTextSize(15);
            detail.setText(content);
            ScrollView scroll = new ScrollView(context);
            scroll.addView(detail);
            dialog = new AlertDialog.Builder(context).setTitle(title).setView(scroll)
                    .setPositiveButton("Done", null).create();
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
