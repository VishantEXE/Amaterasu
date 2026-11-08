package dev.questheld.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class LicenseViewer {
    static void show(Activity activity) {
        String[] labels={"Amaterasu · GPL-3.0-only","Third-party components and notices","ENet · MIT",
            "Opus 1.5.2 · BSD-3-Clause","NanoRS / OBL · MIT","AMD FSR1 · MIT"};
        String[] assets={"Amaterasu-LICENSE.txt","THIRD_PARTY_NOTICES.txt","ENet-LICENSE.txt","Opus-LICENSE.txt","NanoRS-LICENSE.txt","FSR1-LICENSE.txt"};
        new AlertDialog.Builder(activity).setTitle("Open Source Licenses").setItems(labels,(dialog,which)->{
            String body;
            try(InputStream input=activity.getAssets().open(assets[which]); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096]; int count;
                while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
                body=new String(output.toByteArray(),StandardCharsets.UTF_8);
            } catch(IOException e) { body="License text could not be opened. See LICENSE and THIRD_PARTY_NOTICES in the source distribution."; }
            TextView text=new TextView(activity); text.setText(body); text.setTextSize(13); text.setTextIsSelectable(true);
            int edge=(int)(20*activity.getResources().getDisplayMetrics().density); text.setPadding(edge,edge,edge,edge);
            ScrollView scroll=new ScrollView(activity); scroll.addView(text);
            new AlertDialog.Builder(activity).setTitle(labels[which]).setView(scroll).setPositiveButton("Done",null).show();
        }).setNegativeButton("Done",null).show();
    }
}
