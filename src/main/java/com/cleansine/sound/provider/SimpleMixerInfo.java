package com.cleansine.sound.provider;

import javax.sound.sampled.Mixer;

public final class SimpleMixerInfo extends Mixer.Info {
    private final int index;
    private final String deviceID;
    private final int maxLines;

    /**
     * Instantiated only by native!
     */
    private SimpleMixerInfo(int index, String deviceID, int maxLines, String name,
                            String vendor, String description) {
        super(name, vendor, description, "1");
        this.index = index;
        this.deviceID = deviceID;
        this.maxLines = maxLines;
    }

    public int getIndex() {
        return index;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public String getDeviceID() {
        return deviceID;
    }

    public String toMyString() {
        return "SimpleMixerInfo{" + this +
                ", index=" + index +
                ", deviceID='" + deviceID + '\'' +
                ", maxLines=" + maxLines +
                '}';
    }
}
