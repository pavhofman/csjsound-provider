package com.cleansine.sound;

import com.cleansine.sound.provider.SimpleMixer;
import com.cleansine.sound.provider.SimpleMixerInfo;
import com.cleansine.sound.provider.SimpleMixerProvider;

import javax.sound.sampled.AudioFormat;
import java.util.Vector;

public class Main {
    /**
     * Example usage in {@link TestPlayerS16LE}
     */
    public static void main(String[] args) {
        // test playback
        TestPlayerS16LE player = new TestPlayerS16LE(48000, 2);
        int cnt = SimpleMixerProvider.nGetMixerCnt();
        for (int i = 0; i < cnt; i++) {
            SimpleMixerInfo mixerInfo = SimpleMixerProvider.nCreateMixerInfo(i);
            String deviceID = mixerInfo.getDeviceID();
            deviceID = "";
            continue;
        }

//        Vector<AudioFormat> deviceFormats = new Vector<>();
//        SimpleMixer.nGetFormats("itec", true, deviceFormats);
//        deviceFormats.clear();

        Vector<AudioFormat> deviceFormats = new Vector<>();
        SimpleMixer.nGetFormats("0", true, deviceFormats);
        deviceFormats.clear();
        SimpleMixer.nGetFormats("1", true, deviceFormats);

        player.play(
                (info) -> info.getName().startsWith("EXCL: Repro"),
                //(info) -> info.getName().startsWith("EXCL: Micro"),
                null,
                TestPlayerS16LE.Op.SINE_1K, 3000
        );

//        player.play(
//                info -> !info.getName().contains("plughw") && info.getName().contains("itec"),
//                info -> !info.getName().contains("plughw") && info.getName().contains("itec"),
//                //null,
//                TestPlayerS16LE.Op.LOOPBACK, 3000
//        );
        System.exit(0);
    }

}
