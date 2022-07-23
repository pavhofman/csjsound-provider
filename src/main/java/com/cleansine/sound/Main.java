package com.cleansine.sound;

public class Main {
    /**
     * Example usage in {@link TestPlayerS16LE}
     */
    public static void main(String[] args) {
        // test playback
        TestPlayerS16LE player = new TestPlayerS16LE(48000, 2);
        player.play((info) -> !info.getName().contains("plughw") && info.getName().contains("itec"), TestPlayerS16LE.Op.LOOPBACK, 3000);
        System.exit(0);
    }

}
