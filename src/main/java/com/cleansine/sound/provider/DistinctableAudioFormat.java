package com.cleansine.sound.provider;

import javax.sound.sampled.AudioFormat;
import java.util.Objects;

/**
 * Implemented equals/hash methods with fields, to allow proper distinct() method.
 * AudioFormat uses object ids.
 */
class DistinctableAudioFormat extends AudioFormat {

    public DistinctableAudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits,
                                   int channels, int frameSize, boolean bigEndian) {
        super(encoding, sampleRate, sampleSizeInBits, channels, frameSize, sampleRate, bigEndian);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DistinctableAudioFormat that = (DistinctableAudioFormat) o;
        return Objects.equals(encoding, that.encoding)
                && Float.compare(that.sampleRate, sampleRate) == 0
                && sampleSizeInBits == that.sampleSizeInBits
                && channels == that.channels
                && frameSize == that.frameSize
                && bigEndian == that.bigEndian;
    }

    @Override
    public int hashCode() {
        return Objects.hash(encoding, sampleRate, sampleSizeInBits, channels, frameSize, bigEndian);
    }
}
