package dev.questheld.host;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class HostChecks {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        int[] activations={0};
        SessionState.attach(stage->{ if(stage==SessionState.Stage.STREAMING) activations[0]++; });
        long old=SessionState.epoch();
        SessionState.emit(old,SessionState.Stage.SERVER_READY);
        SessionState.emit(old,SessionState.Stage.PAIRED);
        require(activations[0]==0,"Discovery/pairing applied host controls prematurely");
        SessionState.emit(old,SessionState.Stage.STREAMING);
        SessionState.emit(old,SessionState.Stage.STREAMING);
        SessionState.emit(old,SessionState.Stage.CLIENT_DISCOVERED);
        require(activations[0]==1 && SessionState.current()==SessionState.Stage.STREAMING,"Duplicate/regressive stream event");
        SessionState.emit(old,SessionState.Stage.DISCONNECTED);
        SessionState.emit(old,SessionState.Stage.STREAMING);
        require(activations[0]==1,"Disconnected stream was revived");
        SessionState.detach();
        require(SessionState.current()==SessionState.Stage.STOPPED,"Service teardown did not stop the session");
        SessionState.attach(stage->{if(stage==SessionState.Stage.STREAMING) activations[0]++;});
        SessionState.emit(old,SessionState.Stage.DISCONNECTED);
        require(SessionState.current()==SessionState.Stage.LAUNCHED,"Old transport stopped a new session");
        SessionState.emit(SessionState.epoch(),SessionState.Stage.STREAMING);
        require(activations[0]==2,"New session did not become active");
        SessionState.detach();
        require(OverrideOwnership.restoreAudio(7,true,7),"Owned media mute not restored");
        require(!OverrideOwnership.restoreAudio(8,true,7),"User volume change overwritten");
        require(!OverrideOwnership.restoreAudio(7,false,7),"User unmute overwritten");
        require(OverrideOwnership.brightness(1,0,150,true)==OverrideOwnership.Brightness.RESTORE_ALL,"Original brightness not restored");
        require(OverrideOwnership.brightness(60,0,150,true)==OverrideOwnership.Brightness.LEAVE_USER_STATE,"Manual brightness adjustment overwritten");
        require(OverrideOwnership.brightness(1,1,150,true)==OverrideOwnership.Brightness.LEAVE_USER_STATE,"User auto-brightness change overwritten");
        require(OverrideOwnership.brightness(150,0,150,false)==OverrideOwnership.Brightness.RESTORE_MODE,"Interrupted partial dim not recovered");
        require(OverrideOwnership.brightness(150,0,150,true)==OverrideOwnership.Brightness.LEAVE_USER_STATE,"User reset brightness overwritten");
        StreamSettings old4k = new StreamSettings(true, 1, true, 100, 60);
        require(old4k.outputWidth()==3840 && old4k.outputHeight()==2160, "Existing 4K preference changed");
        StreamSettings qhd = new StreamSettings(true, 2, true, 100, 60);
        require(qhd.captureWidth()==1920 && qhd.captureHeight()==1080, "1440p capture size");
        require(qhd.outputWidth()==2560 && qhd.outputHeight()==1440, "1440p output size");
        StreamSettings disabled = new StreamSettings(false, 2, true, 100, 60);
        require(disabled.outputWidth()==1920 && disabled.captureHeight()==1080, "FSR-off must remain native 1080p");
        require(new StreamSettings(true,0,false,20).captureWidth()==1280, "720p mode changed");

        VideoBufferPool pool = new VideoBufferPool();
        byte[] first=pool.acquire(100000), owned=pool.acquire(100000);
        require(first!=owned,"A buffer still in flight was reused");
        pool.release(first);
        require(pool.acquire(90000)==first,"Ordinary encoded buffer was not reused");
        byte[] huge=pool.acquire(2*1024*1024+1);
        pool.release(huge);
        require(pool.retainedBytes()==0,"Large keyframe was retained");
        for(int i=0;i<20;i++) pool.release(new byte[400000]);
        require(pool.retainedBytes()<=VideoBufferPool.MAX_RETAINED_BYTES,"Pool exceeded its memory budget");
        pool.clear(); require(pool.retainedBytes()==0,"Pool did not release its references");
        for(int i=0;i<6;i++) pool.release(new byte[16384]);
        byte[] gameplay=new byte[200000]; pool.release(gameplay);
        require(pool.acquire(190000)==gameplay,"Tiny menu buffers prevented gameplay buffer reuse");
        pool.clear();
        pool.release(new byte[800000]);
        pool.release(new byte[200000]);
        pool.trim();
        require(pool.retainedBytes()==200000,"Pressure trim did not release the large cached buffer");
        pool.release(new byte[800000]);
        require(pool.retainedBytes()==200000,"Large buffers were cached again under pressure");
        SessionState.attach(stage -> {});
        long pressureOwner=SessionState.epoch();
        SessionState.noteMemoryPressure();
        require(SessionState.hasMemoryPressure(pressureOwner),"Active session missed the pressure warning");
        SessionState.detach();
        SessionState.attach(stage -> {});
        require(!SessionState.hasMemoryPressure(SessionState.epoch()),"Pressure warning leaked into a new session");
        SessionState.detach();

        // Reassemble actual data shards across frame/header, FEC-block and large-frame boundaries.
        for(int length:new int[]{1,1336,1337,1344,3000,284921,1300000}) {
            byte[] payload=new byte[length+8192];
            Arrays.fill(payload,(byte)0x6d); // Stale capacity must never reach the wire.
            for(int i=0;i<length;i++) payload[i]=(byte)(i*17+3);
            int payloadSize=1344, shardSize=payloadSize+VideoBlock.HEADER;
            int count=(length+8+payloadSize-1)/payloadSize;
            int maxData=count<=4*212?212:1023, blocks=(count+maxData-1)/maxData;
            ByteBuffer packet=ByteBuffer.allocate(maxData*shardSize);
            int source=0, received=0;
            for(int block=0;block<blocks;block++) {
                int data=Math.min(maxData,count-block*maxData);
                source=VideoBlock.write(packet,payloadSize,payload,length,source,13,72,true,block,blocks,data);
                for(int i=0;i<data;i++) {
                    int start=i*shardSize+VideoBlock.HEADER+(block==0&&i==0?8:0);
                    int end=(i+1)*shardSize;
                    for(int offset=start;offset<end;offset++) {
                        require(packet.get(offset)==(received<length?payload[received]:0),"Access unit corruption or stale pooled data leaked");
                        received++;
                    }
                }
            }
            require(source==length && received>=length,"Fragmented frame lost bytes");
        }

        CongestionGovernor governor = new CongestionGovernor();
        require(!governor.shouldReduce(0,0,0,0),"Stable stream should not reduce bitrate");
        require(governor.shouldReduce(2000,4,0,0),"Queue overflow must trigger adaptation without late frames");
        require(!governor.shouldReduce(4000,12,0,0),"Cooldown should prevent repeated cuts");
        require(!governor.shouldReduce(7000,12,0,0),"Old drops must not trigger another cut");
        require(governor.shouldReduce(9000,12,1,0),"Oversized output must trigger adaptation");
        require(governor.shouldReduce(14000,12,1,2),"Late frames must still trigger adaptation");

        AudioPacer audio = new AudioPacer(5);
        audio.reset(0); audio.sent(2);
        require(audio.waitNs(3)==2,"Audio phase drifted after a short scheduling delay");
        audio.sent(30);
        require(audio.waitNs(30)==0,"Delayed audio should permit a catch-up packet");
        audio.sent(30);
        require(audio.waitNs(30)==5,"Audio catch-up burst was not bounded");

        for(int fps:new int[]{60,120}) {
            FramePacer clock=new FramePacer(fps);
            long period=1000000000L/fps;
            require(clock.due(0),"First frame not due");
            clock.submitted(1);
            require(!clock.due(period),"Frame paced too early");
            require(clock.due(period+1),"Frame paced too late");
        }
        System.out.println("Host checks passed: session generations, override recovery, modes, buffer ownership/budget, fragment reconstruction, congestion recovery, audio clock, 60/120 pacing");
    }
}
