package com.cleansine.sound.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.sound.sampled.*;
import java.util.Arrays;
import java.util.Vector;

final class SimpleMixer extends SimpleLine implements Mixer {
    private static final Logger logger = LoggerFactory.getLogger(SimpleMixer.class);
    private final Mixer.Info mixerInfo;
    private Line.Info[] sourceLineInfos;
    private Line.Info[] targetLineInfos;

    private boolean isOpenedExplicitely = false;
    private boolean isStarted = false;
    private final Vector<Line> sourceLines = new Vector<>();
    private final Vector<Line> targetLines = new Vector<>();

    SimpleMixer(SimpleMixerInfo mInfo) {
        super(new Line.Info(Mixer.class), null);
        this.mixer = this;
        this.mixerInfo = mInfo;
        this.sourceLineInfos = null;
        this.targetLineInfos = null;

        sourceLineInfos = initLineInfos(true);
        targetLineInfos = initLineInfos(false);
    }

    @Nonnull
    private Line.Info[] initLineInfos(boolean isSource) {
        Line.Info info = createDataLineInfo(isSource);
        if (info != null) {
            Line.Info[] infos = new Line.Info[1];
            infos[0] = info;
            return infos;
        } else {
            return new Line.Info[0];
        }
    }

    @Nullable
    private Line.Info createDataLineInfo(boolean isSource) {
        Vector<AudioFormat> deviceFormats = new Vector<>();
        nGetFormats(getDeviceID(), isSource, deviceFormats);
        if (!deviceFormats.isEmpty()) {
            AudioFormat[] formats = deviceFormats.stream()
                    .distinct()
                    .toArray(AudioFormat[]::new);
            // using some minimum buffer size
            return new DataLine.Info(isSource ? SourceDataLine.class : TargetDataLine.class, formats, 32, AudioSystem.NOT_SPECIFIED);
        } else
            return null;
    }

    @Override
    public Line getLine(@Nonnull Line.Info info) {
        Line.Info existingInfo = getLineInfo(info);
        if (existingInfo instanceof DataLine.Info && info instanceof DataLine.Info) {
            DataLine.Info dataLineInfo = (DataLine.Info) existingInfo;
            AudioFormat[] supportedFormats = ((DataLine.Info) info).getFormats();
            int lineBufferSize = ((DataLine.Info) info).getMaxBufferSize();

            if ((supportedFormats != null) && (supportedFormats.length != 0)) {
                // using the last format in the info
                AudioFormat lineFormat = supportedFormats[supportedFormats.length - 1];
                if (dataLineInfo.getLineClass().isAssignableFrom(SimpleSourceDataLine.class)) {
                    return new SimpleSourceDataLine(dataLineInfo, lineFormat, lineBufferSize, this);
                }
                if (dataLineInfo.getLineClass().isAssignableFrom(SimpleTargetDataLine.class)) {
                    return new SimpleTargetDataLine(dataLineInfo, lineFormat, lineBufferSize, this);
                }
            } else {
                throw new IllegalArgumentException("line info " + info + " has no supported formats");
            }
        }
        throw new IllegalArgumentException("Unsupported line info: " + info);
    }

    @Override
    public int getMaxLines(Line.Info info) {
        if (getLineInfo(info) instanceof DataLine.Info)
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
    public synchronized void open() throws LineUnavailableException {
        openLine(true);
    }

    final synchronized void openLine(boolean isExplicitely) throws LineUnavailableException {
        if (!isOpen()) {
            setOpen(true);
            if (isExplicitely)
                isOpenedExplicitely = true;
        }
    }

    synchronized void openLine(Line line) throws LineUnavailableException {
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
        for (Line l : (Vector<Line>) sourceLines.clone()) {
            if (((SimpleDataLine) l).isRunning && (!l.equals(line)))
                return;
        }
        for (Line l : (Vector<Line>) targetLines.clone()) {
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
    Line.Info getLineInfo(@Nonnull Line.Info info) {
        for (Line.Info i : sourceLineInfos) {
            if (info.matches(i))
                return i;
        }

        for (Line.Info i : targetLineInfos) {
            if (info.matches(i))
                return i;
        }
        return null;
    }


    static native void nGetFormats(String deviceID, boolean isSource, Vector formats);

    static native void nStart(long nativePtr, boolean isSource);

    static native void nStop(long nativePtr, boolean isSource);

    /**
     * returns pointer to native struct holding state
     */
    static native long nOpen(String deviceID, boolean isSource, int enc, int rate, int sampleSignBits,
                             int frameBytes, int channels, boolean signed, boolean bigEndian, int bufferBytes)
            throws LineUnavailableException;

    static native void nClose(long nativePtr, boolean isSource);

    static native int nRead(long nativePtr, byte[] bytes, int offset, int len);

    static native int nWrite(long nativePtr, byte[] bytes, int offset, int len);

    static native int nGetBufferBytes(long nativePtr, boolean isSource);

    static native int nGetAvailBytes(long nativePtr, boolean isSource);

    static native boolean nDrain(long nativePtr);

    static native void nFlush(long nativePtr, boolean isSource);

    static native long nGetBytePos(long nativePtr, boolean isSource, long javaPos);
}
