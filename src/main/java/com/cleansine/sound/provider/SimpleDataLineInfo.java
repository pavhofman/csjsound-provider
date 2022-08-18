package com.cleansine.sound.provider;

import javax.annotation.Nonnull;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import java.util.Map;

public final class SimpleDataLineInfo extends DataLine.Info {
    private final Map<AudioFormat, AudioFormat> hwFormatByFormat;

    public SimpleDataLineInfo(Class<?> lineClass, AudioFormat[] formats, int minBufferSize, int maxBufferSize, @Nonnull Map<AudioFormat, AudioFormat> hwFormatByFormat) {
        super(lineClass, formats, minBufferSize, maxBufferSize);
        this.hwFormatByFormat = hwFormatByFormat;
    }

    @Nonnull
    public Map<AudioFormat, AudioFormat> gethwFormatByFormat() {
        return hwFormatByFormat;
    }
}
