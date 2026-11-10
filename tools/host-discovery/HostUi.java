package dev.questheld.host;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Lightweight native UI. Stream settings keep the original preference keys and values. */
final class HostUi extends LinearLayout {
    enum State { READY, NO_NETWORK, STARTING, SHARING, ERROR }
    interface Actions {
        void start();
        void stop();
        void retry();
        void pair(String pin);
        void copyAddress();
        default void connectionModeChanged() {}
        default void hotspotSettings() {}
        default void liveStats() {}
        default boolean supportsAv1(StreamSettings settings) { return false; }
        default void dimPermission() {}
        default void restoreScreen() {}
        default void batterySettings() {}
        default void licenses() {}
    }

    private final SharedPreferences prefs;
    private final Actions actions;
    private final int background, surface, soft, outline, ink, muted, accent, onAccent, accentSoft, danger, dangerSoft;
    private final boolean wide;
    private final Switch fsr, highRefresh;
    private Switch muteHost, dimHost;
    private Button brightnessPermission;
    private TextView dimHint;
    private boolean brightnessAllowed, dimPermissionInitialized, updatingDimState;
    private Button connectionMode, systemHotspot, copyHotspotAddress, statsButton;
    private TextView hotspotInfo, liveStatus, connectionHelp, pairingStatus;
    private final SeekBar bitrate;
    private final TextView status, statusTag, statusBody, addressText, message, resolutionValue, resolutionHint;
    private final TextView bitrateValue, moonlightSummary, moonlightDetail, pairChevron, pairFeedback, settingsHint;
    private final Button primary, h264, hevc, pairButton;
    private final LinearLayout resolutionRow, pairingBody;
    private final View addressRow;
    private final EditText pin;
    private int selectedScale;
    private int selectedCodec;
    private Button av1;
    private TextView codecHint;
    private boolean busy;
    private State currentState = State.READY;
    private String stateKey = "";
    private static final int[] SCALE_VALUES = {0, 2, 1};
    private static final String[] SCALE_LABELS = {"1080p", "1440p", "4K"};
    private static final String[] SCALE_DETAILS = {"1280 × 720 → 1920 × 1080", "1920 × 1080 → 2560 × 1440", "1920 × 1080 → 3840 × 2160"};

    HostUi(Context context, SharedPreferences prefs, Actions actions) {
        super(context);
        this.prefs = prefs;
        this.actions = actions;
        background=color(R.color.background); surface=color(R.color.surface); soft=color(R.color.surface_soft);
        outline=color(R.color.outline); ink=color(R.color.ink); muted=color(R.color.muted);
        accent=color(R.color.accent); onAccent=color(R.color.on_accent); accentSoft=color(R.color.accent_soft);
        danger=color(R.color.danger); dangerSoft=color(R.color.danger_soft);
        wide=getResources().getConfiguration().screenWidthDp >= 760;
        selectedScale=prefs.getInt("fsrScale",0);
        if(selectedScale<0 || selectedScale>2) selectedScale=0;
        int savedCodec=prefs.getInt("codec",StreamSettings.H264);
        selectedCodec=savedCodec==StreamSettings.HEVC || savedCodec==StreamSettings.AV1
                ?savedCodec:StreamSettings.H264;
        setOrientation(VERTICAL);
        setBackgroundColor(background);
        int edge=wide?32:20;

        LinearLayout header=row();
        header.setPadding(dp(edge),dp(16),dp(edge),dp(16));
        ImageView mark=new ImageView(context);
        mark.setImageResource(R.drawable.amaterasu_art);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setBackground(shape(0xffffffff,12,0));
        mark.setClipToOutline(true);
        header.addView(mark,new LayoutParams(dp(44),dp(44)));
        LinearLayout brand=column();
        brand.setPadding(dp(12),0,0,0);
        brand.addView(text("Amaterasu",25,ink,true));
        brand.addView(text("Your screen, anywhere.",12,muted,false));
        header.addView(brand,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        TextView host=text("HOST",11,accent,true);
        host.setLetterSpacing(.12f); host.setPadding(dp(14),dp(8),dp(14),dp(8));
        host.setBackground(shape(accentSoft,20,0));
        header.addView(host);
        addView(header);

        ScrollView scroll=new ScrollView(context);
        scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setPadding(dp(edge),0,dp(edge),dp(20));
        LinearLayout content=wide?row():column();
        content.setGravity(Gravity.TOP);
        LinearLayout left=column(), right=column();
        if(wide) {
            content.addView(left,new LayoutParams(0,LayoutParams.WRAP_CONTENT,.42f));
            content.addView(new View(context),new LayoutParams(dp(20),1));
            content.addView(right,new LayoutParams(0,LayoutParams.WRAP_CONTENT,.58f));
        } else { content.addView(left,fill()); content.addView(right,fill()); }
        scroll.addView(content,new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        addView(scroll,new LayoutParams(LayoutParams.MATCH_PARENT,0,1));

        LinearLayout hero=card(accentSoft);
        statusTag=text("●  READY TO CONNECT",11,accent,true); statusTag.setLetterSpacing(.06f);
        hero.addView(statusTag);
        status=text("Ready to share.",26,ink,true);
        status.setAccessibilityHeading(true); margin(hero,status,12,0);
        statusBody=text("Share your screen and device audio with Moonlight.",14,muted,false);
        statusBody.setLineSpacing(dp(3),1); margin(hero,statusBody,10,0);
        LinearLayout ip=row(); ip.setPadding(dp(14),dp(12),dp(14),dp(12));
        ip.setMinimumHeight(dp(56)); ip.setBackground(touch(soft,16));
        LinearLayout ipLabels=column();
        ipLabels.addView(text("HOST ADDRESS",10,muted,true));
        addressText=text("Checking Wi-Fi…",17,ink,true);
        addressText.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        ipLabels.addView(addressText);
        ip.addView(ipLabels,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        TextView copy=text("Copy",13,accent,true); ip.addView(copy);
        ip.setOnClickListener(v->actions.copyAddress());
        ip.setContentDescription("Copy host address");
        addressRow=ip; margin(hero,ip,20,0);
        TextView local=text("Wi-Fi or mobile hotspot. No internet needed.",12,muted,false);
        margin(hero,local,12,0);
        statsButton=button("Live stats",false);
        statsButton.setVisibility(GONE);
        statsButton.setOnClickListener(v->actions.liveStats());
        margin(hero,statsButton,8,0);
        message=text("",13,danger,false); message.setVisibility(GONE);
        message.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        margin(hero,message,12,0);
        addCard(left,hero);

        LinearLayout connection=card(surface);
        heading(connection,"Connection","Choose how your device reaches Amaterasu.");
        connectionMode=button(directHotspot()?"Direct Wi-Fi Hotspot":"Existing Wi-Fi LAN",false);
        margin(connection,connectionMode,8,8);
        connectionMode.setOnClickListener(v->{
            if(busy) return;
            new AlertDialog.Builder(getContext()).setTitle("Connection mode")
                .setSingleChoiceItems(new String[]{"Existing Wi-Fi LAN","Direct Wi-Fi Hotspot"},directHotspot()?1:0,(dialog,which)->{
                    prefs.edit().putBoolean("directHotspot",which==1).apply();
                    connectionMode.setText(which==1?"Direct Wi-Fi Hotspot":"Existing Wi-Fi LAN");
                    actions.connectionModeChanged(); dialog.dismiss();
                }).setNegativeButton("Cancel",null).show();
        });
        hotspotInfo=text("",12,muted,false); margin(connection,hotspotInfo,4,8);
        systemHotspot=button("Android hotspot settings",false); systemHotspot.setOnClickListener(v->actions.hotspotSettings()); margin(connection,systemHotspot,4,4);
        copyHotspotAddress=button("Copy host address",false);
        copyHotspotAddress.setVisibility(GONE);
        copyHotspotAddress.setOnClickListener(v->actions.copyAddress());
        margin(connection,copyHotspotAddress,4,4);
        connectionHelp=text("",12,muted,false); margin(connection,connectionHelp,10,0);
        pairingStatus=text("",12,accent,true); margin(connection,pairingStatus,8,0);
        liveStatus=text("Server stopped",12,accent,true); margin(connection,liveStatus,12,0);
        addCard(left,connection);

        LinearLayout pairCard=card(surface);
        LinearLayout pairHeading=row(); pairHeading.setMinimumHeight(dp(48));
        LinearLayout pairTitle=column(); pairTitle.addView(text("Connect a device",17,ink,true));
        pairTitle.addView(text("First-time setup & pairing",12,muted,false));
        pairHeading.addView(pairTitle,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        pairChevron=text("+",24,accent,false); pairHeading.addView(pairChevron);
        pairHeading.setBackground(touch(surface,12));
        pairHeading.setContentDescription("Show or hide device pairing instructions");
        pairCard.addView(pairHeading);
        pairingBody=column(); pairingBody.setVisibility(GONE);
        margin(pairCard,pairingBody,12,0);
        pairHeading.setOnClickListener(v->{
            boolean expanded=pairingBody.getVisibility()!=VISIBLE;
            pairingBody.setVisibility(expanded?VISIBLE:GONE); pairChevron.setText(expanded?"−":"+");
        });
        step(pairingBody,"1","Connect both devices","Use the same Wi-Fi network or hotspot.");
        step(pairingBody,"2","Open Moonlight","Select Amaterasu, or add the address above.");
        step(pairingBody,"3","Enter the PIN","Use the four digits shown by Moonlight.");
        pin=new EditText(context); pin.setSingleLine(true); pin.setTextSize(20); pin.setTextColor(ink);
        pin.setHintTextColor(muted); pin.setHint("4-digit PIN"); pin.setLetterSpacing(.15f);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER); pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        pin.setImeOptions(EditorInfo.IME_ACTION_DONE); pin.setSelectAllOnFocus(true);
        pin.setPadding(dp(16),0,dp(16),0); pin.setBackground(shape(soft,14,outline));
        pin.setContentDescription("Four-digit Moonlight pairing PIN");
        pairingBody.addView(pin,new LayoutParams(LayoutParams.MATCH_PARENT,dp(54)));
        pairButton=button("Pair device",false); pairButton.setEnabled(false); pairButton.setAlpha(.45f);
        margin(pairingBody,pairButton,10,0);
        pairButton.setOnClickListener(v->actions.pair(pin.getText().toString()));
        pin.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_DONE && pairButton.isEnabled()) { pairButton.performClick(); return true; }
            return false;
        });
        pin.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                boolean valid=s.toString().matches("[0-9]{4}"); pairButton.setEnabled(valid); pairButton.setAlpha(valid?1:.45f);
            }
            public void afterTextChanged(Editable e){}
        });
        pairFeedback=text("",13,muted,false); pairFeedback.setVisibility(GONE);
        pairFeedback.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        margin(pairingBody,pairFeedback,10,0); addCard(left,pairCard);
        margin(pairingBody,text("Once paired, start sharing here, then launch Screen in Moonlight.",12,muted,false),12,0);

        LinearLayout quality=card(surface);
        settingsHint=heading(quality,"Streaming","Set up your next stream.");
        fsr=new Switch(context); fsr.setChecked(prefs.getBoolean("fsr",false)); tint(fsr,"FSR upscaling");
        toggle(quality,"FSR upscaling","Sharper output from a lower-resolution capture.",fsr);
        divider(quality);
        resolutionRow=row(); resolutionRow.setMinimumHeight(dp(68));
        LinearLayout resolutionLabels=column();
        resolutionLabels.addView(text("Output resolution",14,ink,true));
        resolutionHint=text("",12,muted,false); margin(resolutionLabels,resolutionHint,4,0);
        resolutionRow.addView(resolutionLabels,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        resolutionValue=text("",17,accent,true); resolutionRow.addView(resolutionValue);
        TextView chevron=text("  ›",24,muted,false); resolutionRow.addView(chevron);
        resolutionRow.setBackground(touch(surface,12)); resolutionRow.setOnClickListener(v->selectResolution());
        quality.addView(resolutionRow); divider(quality);
        highRefresh=new Switch(context); highRefresh.setChecked(prefs.getBoolean("highRefresh",false)); tint(highRefresh,"120 FPS (Experimental)");
        toggle(quality,"120 FPS (Experimental)","Requires compatible capture, encoder and client. Uses more power and may reduce stability. Off uses 60 FPS.",highRefresh);
        divider(quality);
        margin(quality,text("Video codec",14,ink,true),14,10);
        LinearLayout codecGroup=row(); codecGroup.setPadding(dp(4),dp(4),dp(4),dp(4));
        codecGroup.setBackground(shape(soft,18,0));
        h264=button("H.264",false); hevc=button("HEVC",false); av1=button("AV1",false);
        codecGroup.addView(h264,new LayoutParams(0,dp(48),1));
        codecGroup.addView(hevc,new LayoutParams(0,dp(48),1));
        codecGroup.addView(av1,new LayoutParams(0,dp(48),1)); quality.addView(codecGroup);
        codecHint=text("",12,muted,false);
        margin(quality,codecHint,10,0);
        addCard(right,quality);

        LinearLayout bandwidth=card(surface);
        LinearLayout bandwidthHeading=row();
        bandwidthHeading.addView(text("Bitrate limit",17,ink,true),new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        bitrateValue=text("",21,accent,true); bandwidthHeading.addView(bitrateValue); bandwidth.addView(bandwidthHeading);
        TextView bandwidthHelp=text("Lower it for a smoother connection on busy Wi-Fi.",12,muted,false);
        margin(bandwidth,bandwidthHelp,6,12);
        bitrate=new SeekBar(context); bitrate.setMax(190); bitrate.setKeyProgressIncrement(5);
        bitrate.setProgress(Math.max(0,Math.min(190,prefs.getInt("bitrateMbps",20)-10)));
        bitrate.setThumbTintList(ColorStateList.valueOf(accent)); bitrate.setProgressTintList(ColorStateList.valueOf(accent));
        bitrate.setProgressBackgroundTintList(ColorStateList.valueOf(outline)); bitrate.setMinimumHeight(dp(48));
        bitrate.setContentDescription("Bitrate limit, 10 to 200 megabits per second");
        bandwidth.addView(bitrate,new LayoutParams(LayoutParams.MATCH_PARENT,dp(48)));
        LinearLayout ends=row(); ends.addView(text("10 Mbps",11,muted,false),new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        ends.addView(text("200 Mbps",11,muted,false)); bandwidth.addView(ends);
        margin(bandwidth,text("Moonlight’s limit also applies. Amaterasu can lower the bitrate to keep up.",12,muted,false),12,0);
        addCard(right,bandwidth);
        LinearLayout audio=card(surface);
        heading(audio,"Audio","Local media output and streamed audio.");
        muteHost=new Switch(context); muteHost.setChecked(prefs.getBoolean("muteHost",true)); tint(muteHost,"Mute Host Speakers While Streaming");
        toggle(audio,"Mute Host Speakers While Streaming","If sound still plays on the host, you may need to set its media volume to 0. Capture compatibility varies by app/firmware; turn this option off if client audio becomes quiet. Calls and alarms are unaffected.",muteHost);
        muteHost.setOnCheckedChangeListener((v,checked)->prefs.edit().putBoolean("muteHost",checked).apply());
        addCard(right,audio);
        LinearLayout power=card(surface);
        heading(power,"Display & Power","Changes begin only when streaming is active.");
        dimHost=new Switch(context); tint(dimHost,"Automatically Dim Host Screen While Streaming");
        dimHint=toggle(power,"Automatically Dim Host Screen While Streaming","",dimHost);
        dimHost.setOnCheckedChangeListener((v,checked)->{if(!updatingDimState) prefs.edit().putBoolean("dimHost",checked).apply();});
        brightnessPermission=button("Allow brightness control",false);
        brightnessPermission.setOnClickListener(v->actions.dimPermission()); margin(power,brightnessPermission,4,12);
        margin(power,text("Brightness and media mute restore on disconnect. After a process kill, reopen Amaterasu to recover saved state. You can also use Android brightness/volume controls. Device validation is pending.",12,muted,false),8,12);
        Button restore=button("Restore screen now",false); restore.setOnClickListener(v->actions.restoreScreen()); power.addView(restore,fill());
        Button battery=button("Battery optimization settings",false); battery.setOnClickListener(v->actions.batterySettings()); margin(power,battery,10,0);
        margin(power,text("An exemption may help vendor background restrictions; Android can still stop streaming under memory pressure.",12,muted,false),10,0);
        addCard(right,power);
        LinearLayout about=card(surface); heading(about,"About",versionLabel()+" · GPLv3");
        Button licenses=button("Open Source Licenses",false); licenses.setOnClickListener(v->actions.licenses()); about.addView(licenses,fill());
        addCard(right,about);
        TextView version=text(versionLabel(),11,muted,false); version.setGravity(Gravity.CENTER);
        margin(right,version,0,4);

        LinearLayout footer=wide?row():column();
        footer.setPadding(dp(edge),dp(12),dp(edge),dp(16)); footer.setBackgroundColor(surface);
        LinearLayout match=column(); moonlightSummary=text("",14,ink,true); moonlightDetail=text("",12,muted,false);
        match.addView(moonlightSummary); margin(match,moonlightDetail,4,0);
        if(wide) footer.addView(match,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        else margin(footer,match,0,12);
        primary=button("Start sharing",true);
        footer.addView(primary,new LayoutParams(wide?dp(220):LayoutParams.MATCH_PARENT,dp(56)));
        primary.setOnClickListener(v->{
            if(currentState==State.SHARING) actions.stop();
            else if(currentState==State.NO_NETWORK) actions.retry();
            else actions.start();
        });
        addView(footer);

        fsr.setOnCheckedChangeListener((v,checked)->{prefs.edit().putBoolean("fsr",checked).apply(); updateSelection();});
        highRefresh.setOnCheckedChangeListener((v,checked)->{prefs.edit().putBoolean("highRefresh",checked).apply(); updateSelection();});
        h264.setOnClickListener(v->selectCodec(StreamSettings.H264));
        hevc.setOnClickListener(v->selectCodec(StreamSettings.HEVC));
        av1.setOnClickListener(v->selectCodec(StreamSettings.AV1));
        bitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){
                bitrateValue.setText((10+progress)+" Mbps");
                if(user) prefs.edit().putInt("bitrateMbps",10+progress).apply();
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        bitrateValue.setText((10+bitrate.getProgress())+" Mbps");
        updateSelection();
        setDimmingPermission(false);
        connectionDetail(directHotspot()?"Hotspot off":"Both devices on the same Wi-Fi LAN. No internet required.","Server stopped");
    }

    boolean directHotspot() { return prefs.getBoolean("directHotspot",false); }
    boolean muteHost() { return muteHost.isChecked(); }
    boolean dimHost() { return dimHost.isChecked(); }
    void setDimmingPermission(boolean allowed) {
        if(dimPermissionInitialized && brightnessAllowed==allowed) return;
        dimPermissionInitialized=true; brightnessAllowed=allowed;
        updatingDimState=true;
        try { dimHost.setChecked(allowed && prefs.getBoolean("dimHost",false)); }
        finally { updatingDimState=false; }
        updateDimmingControls();
    }
    private void updateDimmingControls() {
        dimHost.setEnabled(!busy && brightnessAllowed);
        dimHost.setAlpha(!busy && brightnessAllowed?1:.5f);
        brightnessPermission.setVisibility(brightnessAllowed?GONE:VISIBLE);
        brightnessPermission.setEnabled(!busy);
        dimHint.setText(brightnessAllowed
            ?"Uses minimum physical brightness after streaming starts. The screen stays powered. Your previous brightness restores when sharing ends."
            :busy?"Dimming is inactive. Stop sharing, then allow brightness control in Android settings."
            :"Dimming is inactive until you allow brightness control in Android’s Modify system settings screen.");
    }
    void connectionDetail(String value,String session) {
        hotspotInfo.setText(value); liveStatus.setText(session);
        systemHotspot.setVisibility(directHotspot()?VISIBLE:GONE);
        connectionHelp.setText(directHotspot()
                ?"Choose 5 GHz if available and turn off automatic hotspot shutdown in Android settings. Join that Wi-Fi network on your receiving device."
                :"Open Moonlight or Artemis on the same LAN. Select Amaterasu or add the host address above.");
    }

    void connectionSetup(String address, String pairing) {
        copyHotspotAddress.setVisibility(directHotspot() && address!=null?VISIBLE:GONE);
        if(address!=null) copyHotspotAddress.setText("Copy host address · "+address);
        pairingStatus.setText(pairing);
    }

    void mediaStatus(boolean streaming) {
        if(currentState!=State.SHARING) return;
        statusTag.setText(streaming?"●  STREAMING ACTIVE":"●  SERVER READY");
        status.setText(streaming?"You’re live.":"Ready for your device.");
        statusBody.setText(streaming?"Sending your screen to the connected device. Audio is captured from supported apps.":"Open Amaterasu in Moonlight and launch Screen. You can switch back to your game.");
    }

    StreamSettings settings() { return settings(selectedCodec); }
    private StreamSettings settings(int codec) {
        return new StreamSettings(fsr.isChecked(),selectedScale,codec,10+bitrate.getProgress(),highRefresh.isChecked()?120:60);
    }

    void setState(State state,String ip,String detail,StreamSettings active) {
        String key=state+"|"+directHotspot()+"|"+ip+"|"+detail+"|"+(active==null?"":active.outputWidth()+":"+active.fps);
        if(key.equals(stateKey)) return;
        stateKey=key; currentState=state;
        statsButton.setVisibility(state==State.SHARING?VISIBLE:GONE);
        busy=state==State.SHARING || state==State.STARTING;
        connectionMode.setEnabled(!busy); systemHotspot.setEnabled(!busy);
        muteHost.setEnabled(!busy); updateDimmingControls();
        fsr.setEnabled(!busy); highRefresh.setEnabled(!busy); bitrate.setEnabled(!busy);
        fsr.setAlpha(busy?.5f:1); highRefresh.setAlpha(busy?.5f:1); bitrate.setAlpha(busy?.5f:1);
        settingsHint.setText(busy?"Stop sharing to change these settings.":"Set up your next stream.");
        h264.setEnabled(!busy); hevc.setEnabled(!busy);
        h264.setAlpha(busy?.5f:1); hevc.setAlpha(busy?.5f:1);
        updateSelection();
        addressText.setText(ip==null?"Wi-Fi not connected":ip);
        addressRow.setContentDescription(ip==null?"Host address unavailable":"Copy host address "+ip);
        addressRow.setEnabled(ip!=null); addressRow.setAlpha(ip==null?.5f:1);
        message.setText(detail==null?"":detail); message.setVisibility(detail==null || detail.isEmpty()?GONE:VISIBLE);
        boolean error=state==State.ERROR || state==State.NO_NETWORK;
        statusTag.setTextColor(error?danger:accent);
        primary.setEnabled(state!=State.STARTING); primary.setAlpha(state==State.STARTING?.55f:1);
        if(state==State.SHARING) {
            statusTag.setText("●  SHARING IS ON"); status.setText("Ready for your device.");
            statusBody.setText("Open Amaterasu in Moonlight and launch Screen. You can switch back to your game.");
            primary.setText("Stop sharing"); primary.setTextColor(danger); primary.setBackground(touch(dangerSoft,28));
        } else {
            primary.setBackground(touch(accent,28)); primary.setTextColor(onAccent);
            if(state==State.STARTING) {
                statusTag.setText("●  GETTING READY"); status.setText("Starting your host…");
                statusBody.setText("Preparing screen and device audio sharing."); primary.setText("Starting…");
            } else if(state==State.NO_NETWORK) {
                statusTag.setText("●  CONNECTION NEEDED"); status.setText("Join your network.");
                statusBody.setText(directHotspot()?"Turn on the host’s Wi-Fi hotspot in Android settings."
                        :"Connect both devices to the same Wi-Fi network or mobile hotspot.");
                primary.setText("Check connection");
            } else if(state==State.ERROR) {
                statusTag.setText("●  NEEDS ATTENTION"); status.setText("Let’s try that again.");
                statusBody.setText("Check the message below, adjust your settings, then start sharing."); primary.setText("Start sharing");
            } else {
                statusTag.setText("●  READY TO CONNECT"); status.setText("Ready to share.");
                statusBody.setText("Share your screen and device audio with Moonlight."); primary.setText("Start sharing");
            }
        }
        if(active!=null) updateMatch(active);
    }

    void pairFeedback(String value,boolean success) {
        pairFeedback.setText(value); pairFeedback.setTextColor(success?accent:danger); pairFeedback.setVisibility(VISIBLE);
        if(success) pin.setText("");
    }

    private void selectCodec(int value) {
        if(busy) return;
        selectedCodec=value; prefs.edit().putInt("codec",value).apply(); updateSelection();
    }
    private void selectResolution() {
        if(busy || !fsr.isChecked()) return;
        int index=scaleIndex();
        new AlertDialog.Builder(getContext()).setTitle("FSR output resolution")
                .setSingleChoiceItems(new String[]{"1080p · from 720p","1440p · from 1080p","4K · from 1080p"},index,(dialog,which)->{
                    selectedScale=SCALE_VALUES[which]; prefs.edit().putInt("fsrScale",selectedScale).apply(); updateSelection(); dialog.dismiss();
                }).setNegativeButton("Cancel",null).show();
    }
    private int scaleIndex() { for(int i=0;i<SCALE_VALUES.length;i++) if(SCALE_VALUES[i]==selectedScale) return i; return 0; }
    private void updateSelection() {
        int index=scaleIndex();
        resolutionValue.setText(fsr.isChecked()?SCALE_LABELS[index]:"1080p");
        resolutionHint.setText(fsr.isChecked()?SCALE_DETAILS[index]:"Native capture · enable FSR to upscale");
        resolutionRow.setEnabled(!busy && fsr.isChecked()); resolutionRow.setAlpha(busy || !fsr.isChecked()?.5f:1);
        resolutionRow.setContentDescription("FSR output resolution, "+SCALE_LABELS[index]);
        styleCodec(h264,selectedCodec==StreamSettings.H264);
        styleCodec(hevc,selectedCodec==StreamSettings.HEVC);
        styleCodec(av1,selectedCodec==StreamSettings.AV1);
        boolean av1Available=actions.supportsAv1(settings(StreamSettings.AV1));
        av1.setEnabled(!busy && av1Available);
        av1.setAlpha(av1.isEnabled()?1:.5f);
        codecHint.setText(av1Available?"AV1 (Experimental) requires a compatible client. Auto can fall back to HEVC or H.264."
                :"AV1 (Experimental) needs a hardware encoder for this resolution and frame rate.");
        updateMatch(settings());
    }
    private void updateMatch(StreamSettings selected) {
        String resolution=selected.outputWidth()==3840?"4K":selected.outputWidth()==2560?"1440p":"1080p";
        moonlightSummary.setText("Set Moonlight to "+resolution+" · "+selected.fps+" FPS"+(selected.fps==120?" (Experimental)":""));
        moonlightDetail.setText(StreamSettings.codecName(selected.codec)
                +(selected.codec==StreamSettings.H264?"":" / Auto")+" · stereo audio");
    }
    private void styleCodec(Button view,boolean selected) {
        view.setSelected(selected); view.setTextColor(selected?accent:muted);
        view.setBackground(touch(selected?accentSoft:soft,14));
        view.setContentDescription(view.getText()+ (selected?", selected":""));
    }
    private void tint(Switch view,String name) {
        view.setContentDescription(name); view.setShowText(false); view.setSwitchMinWidth(dp(52));
        GradientDrawable track=shape(outline,16,0); track.setSize(dp(52),dp(30));
        GradientDrawable thumb=shape(muted,12,0); thumb.setSize(dp(24),dp(24));
        view.setTrackDrawable(track); view.setThumbDrawable(thumb); view.setSplitTrack(false);
        view.setButtonDrawable((android.graphics.drawable.Drawable)null);
        view.setSwitchPadding(0); view.setThumbTextPadding(0);
        view.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,muted}));
        view.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accentSoft,outline}));
        view.setMinimumHeight(dp(48));
    }
    private TextView toggle(LinearLayout parent,String title,String subtitle,Switch control) {
        LinearLayout row=row(); row.setMinimumHeight(dp(76));
        LinearLayout labels=column(); labels.setPadding(0,dp(12),dp(16),dp(12));
        labels.addView(text(title,14,ink,true)); TextView body=text(subtitle,12,muted,false); margin(labels,body,4,0);
        row.addView(labels,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1)); row.addView(control); parent.addView(row);
        return body;
    }
    private TextView heading(LinearLayout parent,String title,String subtitle) {
        TextView label=text(title,19,ink,true); label.setAccessibilityHeading(true); parent.addView(label);
        TextView hint=text(subtitle,12,muted,false); margin(parent,hint,4,10); return hint;
    }
    private void step(LinearLayout parent,String number,String title,String body) {
        LinearLayout line=row(); line.setGravity(Gravity.TOP);
        TextView badge=text(number,12,accent,true); badge.setGravity(Gravity.CENTER); badge.setBackground(shape(accentSoft,16,0));
        line.addView(badge,new LayoutParams(dp(28),dp(28)));
        LinearLayout labels=column(); labels.setPadding(dp(12),0,0,0); labels.addView(text(title,13,ink,true));
        margin(labels,text(body,12,muted,false),3,0); line.addView(labels,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1)); margin(parent,line,0,16);
    }
    private int color(int id) { return getContext().getColor(id); }
    private String versionLabel() {
        String version="0.6.7";
        try {
            android.content.pm.PackageInfo info=getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),0);
            if(info!=null && info.versionName!=null) version=info.versionName;
        } catch(android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) {}
        return "Amaterasu · "+version;
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LayoutParams fill() { return new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT); }
    private TextView text(String value,int size,int color,boolean medium) {
        TextView view=new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL)); view.setIncludeFontPadding(false);
        return view;
    }
    private Button button(String value,boolean filled) {
        Button view=new Button(getContext()); view.setText(value); view.setTextSize(14); view.setAllCaps(false);
        view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setPadding(dp(16),0,dp(16),0);
        view.setTextColor(filled?onAccent:accent); view.setBackground(touch(filled?accent:accentSoft,24));
        return view;
    }
    private LinearLayout card(int color) {
        LinearLayout card=column(); card.setPadding(dp(20),dp(20),dp(20),dp(20)); card.setBackground(shape(color,24,0)); return card;
    }
    private void addCard(LinearLayout parent,View card) { margin(parent,card,0,16); }
    private void margin(LinearLayout parent,View child,int top,int bottom) {
        LayoutParams lp=fill(); lp.topMargin=dp(top); lp.bottomMargin=dp(bottom); parent.addView(child,lp);
    }
    private void divider(LinearLayout parent) { View line=new View(getContext()); line.setBackgroundColor(outline); parent.addView(line,new LayoutParams(LayoutParams.MATCH_PARENT,dp(1))); }
    private GradientDrawable shape(int color,int radius,int border) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if(border!=0) drawable.setStroke(dp(1),border); return drawable;
    }
    private RippleDrawable touch(int color,int radius) {
        return new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x22000000),shape(color,radius,0),null);
    }
}
