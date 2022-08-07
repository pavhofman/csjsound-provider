package com.cleansine.sound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.sound.sampled.*;
import java.util.Arrays;
import java.util.Optional;

/**
 * Only S16LE supported!
 */
public class TestPlayerS16LE {
    private static final Logger logger = LoggerFactory.getLogger(TestPlayerS16LE.class);
    public static final int BUFFER_BYTES = 131072;
    private final int samplerate;
    private final int sampleBits;
    private final int channels;

    public TestPlayerS16LE(int samplerate, int channels) {
        this.samplerate = samplerate;
        this.sampleBits = 32;
        this.channels = channels;
    }


    public void play(@Nonnull Selector sourceSelector, @Nullable Selector targetSelector, @Nonnull Op what, int length_ms) {
        final AudioFormat af = new AudioFormat(samplerate, sampleBits, channels, true, false);
        final DataLine.Info sLineInfo = new DataLine.Info(SourceDataLine.class, af);
        final DataLine.Info tLineInfo = new DataLine.Info(TargetDataLine.class, af);
        try {
            Mixer.Info[] infos = AudioSystem.getMixerInfo();
            Optional<Mixer.Info> sourceInfo = Arrays.stream(infos)
                    .filter(i -> sourceSelector.filter(i) && !i.getName().contains("Port"))
                    .findFirst();
            assert (sourceInfo.isPresent());
            assert (sourceInfo.get() instanceof SourceDataLine);
            Mixer sMixer = AudioSystem.getMixer(sourceInfo.get());
//            DataLine.Info testInfo = new DataLine.Info(SourceDataLine.class, new AudioFormat(48000, 32, 2, true, false));
//            boolean isSupported = sMixer.isLineSupported(testInfo);
            SourceDataLine sLine = (SourceDataLine) sMixer.getLine(sLineInfo);
            //AudioFormat naf = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000, 32, 2, 8, 48000, false);
            sLine.open(af, BUFFER_BYTES);
            TargetDataLine tLine = null;
            int bufferBytes;
            if (what.doLoopback) {
                Optional<Mixer.Info> targetInfo = Arrays.stream(infos)
                        .filter(i -> targetSelector.filter(i) && !i.getName().contains("Port"))
                        .findFirst();
                assert (targetInfo.isPresent());
                assert (targetInfo.get() instanceof TargetDataLine);
                tLine = (TargetDataLine) AudioSystem.getMixer(targetInfo.get()).getLine(tLineInfo);
                tLine.open(af, BUFFER_BYTES);
                bufferBytes = tLine.getBufferSize();
                tLine.start();
            } else
                bufferBytes = sLine.getBufferSize();
            int frameBytes = af.getFrameSize();
            int framesInWrite = 6144;
            double[] samples = what.getSamples(samplerate, length_ms);

            int toneIdx = 0;
            //sLine.start();

            while (toneIdx + framesInWrite < samples.length) {
                byte[] buffer;
                if (what.doLoopback) {
                    buffer = new byte[framesInWrite * frameBytes];
                    int countRead = tLine.read(buffer, 0, buffer.length);
                } else {
                    buffer = convertToBytes(samples, toneIdx, framesInWrite, frameBytes);
                }
                logger.debug("Playback before writing: availbytes " + sLine.available());
                int countWritten = sLine.write(buffer, 0, buffer.length);
                logger.debug("Playback after writing: availbytes " + sLine.available());

                toneIdx += framesInWrite;
            }

            sLine.close();
            if (tLine != null)
                tLine.close();
        } catch (LineUnavailableException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    private byte[] convertToBytes(double[] sines, int toneIdx, int framesInWrite, int frameBytes) {
        byte[] buffer = new byte[framesInWrite * frameBytes];
        int limit = toneIdx + framesInWrite;
        int bufferIdx = 0;
        byte[] sampleBytes = new byte[4];
        for (int i = toneIdx; i < limit; ++i) {
            double sine = sines[i];
            int sample = (int) (sine * Integer.MAX_VALUE);
            sampleBytes[0] = (byte) (sample & 0xff);
            sampleBytes[1] = (byte) ((sample >> 8) & 0xff);
            sampleBytes[2] = (byte) ((sample >> 16) & 0xff);
            sampleBytes[3] = (byte) ((sample >> 24) & 0xff);

            for (int ch = 0; ch < channels; ++ch) {
                for (byte sampleByte : sampleBytes) {
                    buffer[bufferIdx] = sampleByte;
                    ++bufferIdx;
                }
            }
        }
        return buffer;
    }

    private static double[] createSineSamples(double freq, int samplerate, int ms) {
        int samples = (ms * samplerate) / 1000;
        double[] output = new double[samples];
        //
        double period = (double) samplerate / freq;
        for (int i = 0; i < output.length; i++) {
            double angle = 2.0 * Math.PI * i / period;
            output[i] = 0.2 * Math.sin(angle);
        }

        return output;
    }

    public enum Op {
        SINE_1K(false) {
            @Override
            public double[] getSamples(int samplerate, int ms) {
                return createSineSamples(1000, samplerate, ms);
            }
        },
        SINE_10K(false) {
            public double[] getSamples(int samplerate, int ms) {
                return createSineSamples(10000, samplerate, ms);
            }
        },
        LOOPBACK(true) {
            public double[] getSamples(int samplerate, int ms) {
                return new double[(ms * samplerate) / 1000];
            }
        };

        final boolean doLoopback;

        Op(boolean doLoopback) {
            this.doLoopback = doLoopback;
        }

        public abstract double[] getSamples(int samplerate, int ms);
    }

    @FunctionalInterface
    interface Selector {
        boolean filter(Mixer.Info info);
    }
}
