package com.cleansine.sound.provider;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;

public final class SimpleDataLineInfo extends DataLine.Info {
    private boolean use24bits;

    public SimpleDataLineInfo(Class<?> lineClass, AudioFormat[] formats, int minBufferSize, int maxBufferSize, boolean use24bits) {
        super(lineClass, formats, minBufferSize, maxBufferSize);
        this.use24bits = use24bits;
    }

    public boolean shouldUse24bits() {
        return use24bits;
    }
}
