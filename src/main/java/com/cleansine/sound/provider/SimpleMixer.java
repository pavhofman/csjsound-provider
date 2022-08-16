package com.cleansine.sound.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;

final class SimpleMixer extends SimpleLine implements Mixer {
    private static final Logger logger = LoggerFactory.getLogger(SimpleMixer.class);
    private final Mixer.Info mixerInfo;
    private final SimpleDataLineInfo[] sourceLineInfos;
    private final SimpleDataLineInfo[] targetLineInfos;

    private boolean isOpenedExplicitely = false;
    private boolean isStarted = false;
    private final Vector<Line> sourceLines = new Vector<>();
    private final Vector<Line> targetLines = new Vector<>();

    SimpleMixer(SimpleMixerInfo mInfo) {
        super(new Line.Info(Mixer.class), null);
        this.mixer = this;
        this.mixerInfo = mInfo;
        this.sourceLineInfos = initLineInfos(true);
        this.targetLineInfos = initLineInfos(false);
    }

    @Nonnull
    private SimpleDataLineInfo[] initLineInfos(boolean isSource) {
        SimpleDataLineInfo info = createDataLineInfo(isSource);
        if (info != null) {
            SimpleDataLineInfo[] infos = new SimpleDataLineInfo[1];
            infos[0] = info;
            return infos;
        } else {
            return new SimpleDataLineInfo[0];
        }
    }

    @Nullable
    private SimpleDataLineInfo createDataLineInfo(boolean isSource) {
        Vector<AudioFormat> deviceFormats = new Vector<>();
        nGetFormats(getDeviceID(), isSource, deviceFormats);
        if (!deviceFormats.isEmpty()) {
            // replacing combination 24 validbits/32 storebits with 32/32 to comply with AudioFormat contract for PCM encoding, remembering in line info
            boolean use24bits = false;
            List<Integer> idsWithUnspecified24bits = new ArrayList<>();
            for (int i = 0; i < deviceFormats.size(); ++i) {
                AudioFormat format = deviceFormats.get(i);
                // use24bits can be determined only from formats with frameSize/channels specified
                if (format.getSampleSizeInBits() == 24) {
                    if (format.getFrameSize() == 4 * format.getChannels()) {
                        AudioFormat modifiedFormat = new DistinctableAudioFormat(
                                format.getEncoding(),
                                format.getSampleRate(),
                                32,
                                format.getChannels(),
                                format.getFrameSize(),
                                format.isBigEndian()
                        );
                        // replacing in the vector
                        deviceFormats.set(i, modifiedFormat);
                        use24bits = true;
                        logger.debug("Using modified 32bit format " + modifiedFormat + " instead of the hardware format " + format);
                    } else if (format.getFrameSize() == AudioSystem.NOT_SPECIFIED)
                        // storing vector index for modifications in the next step
                        idsWithUnspecified24bits.add(i);
                }
            }
            if (use24bits) {
                // must modify the unspecified 24bits formats
                for (Integer id : idsWithUnspecified24bits) {
                    AudioFormat format = deviceFormats.get(id);
                    AudioFormat modifiedFormat = new DistinctableAudioFormat(
                            format.getEncoding(),
                            format.getSampleRate(),
                            32,
                            AudioSystem.NOT_SPECIFIED,
                            AudioSystem.NOT_SPECIFIED,
                            format.isBigEndian()
                    );
                    // replacing in the vector
                    deviceFormats.set(id, modifiedFormat);
                    logger.debug("Using modified 32bit format " + modifiedFormat + " instead of the hardware format " + format);
                }
            }
            AudioFormat[] formats = deviceFormats.stream()
                    // removing duplicate values
                    .distinct()
                    .toArray(AudioFormat[]::new);
            // using some minimum buffer size
            return new SimpleDataLineInfo(isSource ? SourceDataLine.class : TargetDataLine.class, formats, 32, AudioSystem.NOT_SPECIFIED, use24bits);
        } else
            return null;
    }

    @Override
    public Line getLine(@Nonnull Line.Info info) {
        SimpleDataLineInfo existingInfo = getLineInfo(info);
        if (existingInfo != null) {
            int lineBufferSize = ((DataLine.Info) info).getMaxBufferSize();

            AudioFormat lineFormat = getLastFullySpecifiedFormat(existingInfo);
            if (lineFormat != null) {
                if (existingInfo.getLineClass().isAssignableFrom(SimpleSourceDataLine.class)) {
                    return new SimpleSourceDataLine(existingInfo, lineFormat, lineBufferSize, this, existingInfo.shouldUse24bits());
                }
                if (existingInfo.getLineClass().isAssignableFrom(SimpleTargetDataLine.class)) {
                    return new SimpleTargetDataLine(existingInfo, lineFormat, lineBufferSize, this, existingInfo.shouldUse24bits());
                }
            } else {
                throw new IllegalArgumentException("line info " + info + " has no supported formats");
            }
        }
        throw new IllegalArgumentException("Unsupported line info: " + info);
    }

    @Nullable
    private AudioFormat getLastFullySpecifiedFormat(@Nonnull SimpleDataLineInfo info) {
        AudioFormat[] supportedFormats = info.getFormats();
        if ((supportedFormats != null) && (supportedFormats.length != 0)) {
            for (int i = supportedFormats.length - 1; i >= 0; --i) {
                AudioFormat format = supportedFormats[i];
                if (SimpleMixerProvider.isFullySpecifiedFormat(format)) {
                    return format;
                }
            }
        }
        return null;
    }

    @Override
    public int getMaxLines(Line.Info info) {
        if (getLineInfo(info) != null)
            return ((SimpleMixerInfo) getMixerInfo()).getMaxLines();
        else
            return 0;
    }

    String getDeviceID() {
        return ((SimpleMixerInfo) getMixerInfo()).getDeviceID();
    }

    // called from native!
    @SuppressWarnings("unused")
    private static void addFormat(Vector<AudioFormat> v, int bits, int frameBytes, int channels,
                                  int rate, int encoding, boolean isSigned, boolean isBigEndian) {
        if (encoding != SimpleDataLine.PCM_ENCODING) {
            logger.error("SimpleMixer.addFormat called with unsupported encoding: " + encoding);
            return;
        }
        AudioFormat.Encoding enc = isSigned ? AudioFormat.Encoding.PCM_SIGNED : AudioFormat.Encoding.PCM_UNSIGNED;
        if (frameBytes <= 0) {
            if (channels > 0) {
                // 24bits -> 3 bytes, but 25bits -> 4 bytes
                frameBytes = ((bits + 7) / 8) * channels;
            } else {
                frameBytes = AudioSystem.NOT_SPECIFIED;
            }
        }
        v.add(new DistinctableAudioFormat(enc, (float) rate, bits, channels, frameBytes, isBigEndian));
    }

    @Override
    public Mixer.Info getMixerInfo() {
        return mixerInfo;
    }

    @Override
    public Line.Info[] getSourceLineInfo() {
        return Arrays.copyOf(sourceLineInfos, sourceLineInfos.length);
    }

    @Override
    public Line.Info[] getTargetLineInfo() {
        return Arrays.copyOf(targetLineInfos, targetLineInfos.length);
    }

    @Override
    public Line.Info[] getSourceLineInfo(Line.Info info) {
        return Arrays.stream(sourceLineInfos)
                .filter(info::matches)
                .toArray(Line.Info[]::new);
    }

    @Override
    public Line.Info[] getTargetLineInfo(Line.Info info) {
        return Arrays.stream(targetLineInfos)
                .filter(info::matches)
                .toArray(Line.Info[]::new);
    }

    @Override
    public boolean isLineSupported(Line.Info info) {
        return getLineInfo(info) != null;
    }

    @Override
    public Line[] getSourceLines() {
        synchronized (sourceLines) {
            return sourceLines.toArray(new Line[0]);
        }
    }

    @Override
    public Line[] getTargetLines() {
        synchronized (targetLines) {
            return targetLines.toArray(new Line[0]);
        }
    }

    @Override
    public void synchronize(Line[] lines, boolean maintainSync) {
        throw new IllegalArgumentException("Synchronization not supported.");
    }

    @Override
    public void unsynchronize(Line[] lines) {
        throw new IllegalArgumentException("Synchronization not supported.");
    }

    @Override
    public boolean isSynchronizationSupported(Line[] lines, boolean maintainSync) {
        return false;
    }

    @Override
    public synchronized void open() {
        openLine(true);
    }

    synchronized void openLine(boolean isExplicitely) {
        if (!isOpen()) {
            setOpen(true);
            if (isExplicitely)
                isOpenedExplicitely = true;
        }
    }

    synchronized void openLine(Line line) {
        if (this.equals(line))
            // no action
            return;

        if (isSourceLine(line.getLineInfo())) {
            if (!sourceLines.contains(line)) {
                openLine(false);
                sourceLines.addElement(line);
            }
        } else if (isTargetLine(line.getLineInfo())) {
            if (!targetLines.contains(line)) {
                openLine(false);
                targetLines.addElement(line);
            }
        } else {
            logger.error("Unknown line received for AbstractMixer.open(Line): " + line);
        }
    }

    synchronized void closeLine(Line line) {
        if (this.equals(line))
            // no action
            return;

        sourceLines.removeElement(line);
        targetLines.removeElement(line);

        if (sourceLines.isEmpty() && targetLines.isEmpty() && !isOpenedExplicitely) {
            close();
        }
    }

    @Override
    public synchronized void close() {
        if (isOpen()) {
            for (Line line : getSourceLines()) {
                line.close();
            }

            for (Line line : getTargetLines()) {
                line.close();
            }
            setOpen(false);
        }
        isOpenedExplicitely = false;
    }

    synchronized void start(Line line) {
        if (this.equals(line))
            // no action
            return;

        if (!isStarted) {
            isStarted = true;
        }
    }

    synchronized void stopLine(Line line) {
        if (this.equals(line))
            // no action
            return;
        // return if any other line is running
        for (Line l : new Vector<>(sourceLines)) {
            if (((SimpleDataLine) l).isRunning && (!l.equals(line)))
                return;
        }
        for (Line l : new Vector<>(targetLines)) {
            if (((SimpleDataLine) l).isRunning && (!l.equals(line)))
                return;
        }

        isStarted = false;
    }

    boolean isSourceLine(Line.Info info) {
        for (Line.Info i : sourceLineInfos) {
            if (info.matches(i))
                return true;
        }
        return false;
    }

    boolean isTargetLine(Line.Info info) {
        for (Line.Info i : targetLineInfos) {
            if (info.matches(i))
                return true;
        }
        return false;
    }

    @Nullable
    SimpleDataLineInfo getLineInfo(@Nonnull Line.Info info) {
        for (SimpleDataLineInfo i : sourceLineInfos) {
            if (info.matches(i))
                return i;
        }

        for (SimpleDataLineInfo i : targetLineInfos) {
            if (info.matches(i))
                return i;
        }
        return null;
    }


    static native void nGetFormats(String deviceID, boolean isSource, Vector formats);

    static native void nStart(long nativePtr, boolean isSource);

    static native void nStop(long nativePtr, boolean isSource);

    /**
     * @return pointer to native struct holding state or 0 (= NULL)
     */
    static native long nOpen(String deviceID, boolean isSource, int enc, int rate, int sampleSignBits,
                             int frameBytes, int channels, boolean signed, boolean bigEndian, int bufferBytes)
            throws LineUnavailableException;

    static native void nClose(long nativePtr, boolean isSource);

    static native int nRead(long nativePtr, byte[] bytes, int offset, int len);

    static native int nWrite(long nativePtr, byte[] bytes, int offset, int len);

    static native int nGetBufferBytes(long nativePtr, boolean isSource);

    static native int nGetAvailBytes(long nativePtr, boolean isSource);

    static native void nDrain(long nativePtr);

    static native void nFlush(long nativePtr, boolean isSource);

    static native long nGetBytePos(long nativePtr, boolean isSource, long javaPos);
}
