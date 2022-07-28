package com.cleansine.sound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.Arrays;
import java.util.Optional;

/**
 * Only S16LE supported!
 */
public class TestPlayerS16LE {
    private static final Logger logger = LoggerFactory.getLogger(TestPlayerS16LE.class);
    private final int samplerate;
    private final int sampleBits;
    private final int channels;

    public TestPlayerS16LE(int samplerate, int channels) {
        this.samplerate = samplerate;
        this.sampleBits = 16;
        this.channels = channels;
    }


    public void play(Selector selector, Op what, int length_ms) {
        final AudioFormat af = new AudioFormat(samplerate, sampleBits, channels, true, false);
        final DataLine.Info sLineInfo = new DataLine.Info(SourceDataLine.class, af);
        final DataLine.Info tLineInfo = new DataLine.Info(TargetDataLine.class, new AudioFormat(samplerate, sampleBits, 2, true, false));
        try {
            Mixer.Info[] infos = AudioSystem.getMixerInfo();
            Optional<Mixer.Info> info = Arrays.stream(infos)
                    .filter(i -> selector.filter(i) && !i.getName().contains("Port"))
                    .findFirst();
            assert (info.isPresent());
            SourceDataLine sLine = (SourceDataLine) AudioSystem.getMixer(info.get()).getLine(sLineInfo);
            TargetDataLine tLine = (TargetDataLine) AudioSystem.getMixer(info.get()).getLine(tLineInfo);
            sLine.open(af, this.samplerate);
            tLine.open(af, this.samplerate);
            tLine.start();

            int bufferBytes = sLine.getBufferSize();
            int frameBytes = af.getFrameSize();
            int framesInWrite = bufferBytes / frameBytes;
            double[] samples = what.getSamples(samplerate, length_ms);

            int toneIdx = 0;
            boolean sLineStarted = false;
            while (toneIdx + framesInWrite < samples.length) {
                byte[] buffer;
                if (what.doLoopback) {
                    buffer = new byte[framesInWrite * frameBytes];
                    int countRead = tLine.read(buffer, 0, buffer.length);
                } else {
                    buffer = convertToBytes(samples, toneIdx, framesInWrite, frameBytes);
                }
                int countWritten = sLine.write(buffer, 0, buffer.length);
                if (!sLineStarted) {
                    sLine.start();
                    sLineStarted = true;
                }

                toneIdx += framesInWrite;
            }

            sLine.close();
            tLine.close();
        } catch (LineUnavailableException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    private byte[] convertToBytes(double[] sines, int toneIdx, int framesInWrite, int frameBytes) {
        byte[] buffer = new byte[framesInWrite * frameBytes];
        int limit = toneIdx + framesInWrite;
        int bufferIdx = 0;
        for (int i = toneIdx; i < limit; ++i) {
            double sine = sines[i];
            short sample = (short) (sine * Short.MAX_VALUE);
            byte[] sampleBytes = new byte[2];
            sampleBytes[0] = (byte) (sample & 0xff);
            sampleBytes[1] = (byte) ((sample >> 8) & 0xff);

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
