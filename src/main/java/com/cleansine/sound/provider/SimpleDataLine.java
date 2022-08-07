package com.cleansine.sound.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;

abstract class SimpleDataLine extends SimpleLine implements DataLine {

    private static final Logger logger = LoggerFactory.getLogger(SimpleSourceDataLine.class);
    protected static final int PCM_ENCODING = 0;
    private static final int DEFAULT_BUFFER_TIME_MS = 500;
    protected final String deviceID;
    protected final boolean isSource;
    protected AudioFormat format;
    protected int bufferBytes;
    protected final Object lock = new Object();
    protected final Object lockNative = new Object();
    protected volatile boolean isRunning;
    protected long nativePtr;
    protected int checkTimeMS;
    protected volatile boolean flushing = false;
    protected volatile long bytePos;
    // if in between start() and stop() calls
    protected volatile boolean inIO = false;
    // if a write operation occurred in stopped state
    protected volatile boolean writtenWhenStopped = false;
    protected volatile boolean drained = false; // set to true when drain function returns, set to false in write()
    protected volatile boolean isStarted;
    protected volatile boolean isActive;

    protected volatile boolean use24bits;

    //protected FileOutputStream os = null;

    protected SimpleDataLine(DataLine.Info info, SimpleMixer mixer, @Nonnull AudioFormat format, int bufferBytes, String deviceID, boolean isSource, boolean use24bits) {
        super(info, mixer);
        this.format = format;
        this.bufferBytes = bufferBytes;
        this.deviceID = deviceID;
        this.checkTimeMS = 2;  // timeout to check whether all data have been read/written
        this.isSource = isSource;
        this.use24bits = use24bits;
    }


    public void open(@Nonnull AudioFormat format, int bufferSize) throws LineUnavailableException {
        final AudioFormat hwFormat = determineHwFormat(format);
        //noinspection SynchronizeOnNonFinalField
        synchronized (mixer) {
            if (!isOpen()) {
                mixer.openLine(this);
                try {
                    doOpen(hwFormat, bufferSize);
                    this.format = format;
                    setOpen(true);
                } catch (LineUnavailableException e) {
                    mixer.closeLine(this);
                    throw e;
                }
            } else {
                if (!format.matches(getFormat())) {
                    throw new IllegalStateException("Line is already open with format " + getFormat());
                }
                if (bufferSize != AudioSystem.NOT_SPECIFIED && bufferSize != getBufferSize()) {
                    throw new IllegalStateException("Line is already open with buffer size " + getBufferSize());
                }
            }
        }

//        File file = new File("java.raw");
//        try {
//            file.createNewFile();
//            this.os = new FileOutputStream(file);
//        } catch (Exception e) {
//            logger.error("Failed creating file", e);
//        }
    }


    @Nonnull
    private AudioFormat determineHwFormat(@Nonnull AudioFormat format) {
        if (format.getSampleSizeInBits() == 32 && this.use24bits) {
            // hardware needs 24 bits
            AudioFormat hwFormat = new DistinctableAudioFormat(
                    format.getEncoding(),
                    format.getSampleRate(),
                    24,
                    format.getChannels(),
                    format.getFrameSize(),
                    format.isBigEndian()
            );
            logger.debug("Using original 24bit format " + hwFormat + " instead of the requested format " + format);
            return hwFormat;
        } else {
            return format;
        }
    }

    public void open(AudioFormat format) throws LineUnavailableException {
        open(format, AudioSystem.NOT_SPECIFIED);
    }

    @Override
    public final void start() {
        //noinspection SynchronizeOnNonFinalField
        synchronized (mixer) {
            if (isOpen()) {
                if (!this.isRunning) {
                    mixer.start(this);
                    doStart();
                    isRunning = true;
                }
            }
        }

        synchronized (lock) {
            lock.notifyAll();
        }
    }

    @Override
    public void stop() {
        //noinspection SynchronizeOnNonFinalField
        synchronized (mixer) {
            if (isOpen()) {
                if (isRunning) {
                    doStop();
                    mixer.stopLine(this);
                    isRunning = false;
                    if (isStarted && (!isActive()))
                        setStarted(false);
                }
            }
        }

        synchronized (lock) {
            lock.notifyAll();
        }
    }

    @Override
    public boolean isRunning() {
        return isStarted;
    }

    @Override
    public boolean isActive() {
        return isActive;
    }

    @Override
    public long getMicrosecondPosition() {
        long us = getLongFramePosition();
        if (us != AudioSystem.NOT_SPECIFIED) {
            us = (long) (((double) us) / format.getFrameRate() * 1000000d);
        }
        return us;
    }

    @Override
    public int getBufferSize() {
        return bufferBytes;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public float getLevel() {
        return (float) AudioSystem.NOT_SPECIFIED;
    }

    final void setStarted(boolean started) {
        synchronized (this) {
            if (this.isStarted != started)
                this.isStarted = started;
        }
    }

    void setActive(boolean active) {
        synchronized (this) {
            if (this.isActive != active)
                this.isActive = active;
        }
    }

    @Override
    public final void open() throws LineUnavailableException {
        // using current values, no change requrested
        open(format, bufferBytes);
    }

    @Override
    public final void close() {
        //noinspection SynchronizeOnNonFinalField
        synchronized (mixer) {
            if (isOpen()) {
                stop();
                setOpen(false);
                doClose();
                mixer.closeLine(this);
            }
        }
        try {
            //this.os.close();
        } catch (Exception e) {
            logger.error("Error closing stream", e);
        }
    }

    public int getFramePosition() {
        return (int) getLongFramePosition();
    }

    void doOpen(AudioFormat hwFormat, int bufferBytes) throws LineUnavailableException {
        if (!isPCMEncoding(hwFormat))
            throw new IllegalArgumentException("Unsupported encoding " + hwFormat.getEncoding() + ", only PCM is supported");

        if (bufferBytes <= AudioSystem.NOT_SPECIFIED) {
            // setting default value
            bufferBytes = (int) ((long) DEFAULT_BUFFER_TIME_MS * hwFormat.getFrameRate() / 1000.0f * hwFormat.getFrameSize());
            // aligning to frames
            bufferBytes = (bufferBytes / hwFormat.getFrameSize()) * hwFormat.getFrameSize();
        }
        // aligning to frames
        bufferBytes = (bufferBytes / hwFormat.getFrameSize()) * hwFormat.getFrameSize();

        boolean isSigned = hwFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED);
        nativePtr = SimpleMixer.nOpen(deviceID, isSource, PCM_ENCODING, (int) hwFormat.getSampleRate(), hwFormat.getSampleSizeInBits(), hwFormat.getFrameSize(),
                hwFormat.getChannels(), isSigned, hwFormat.isBigEndian(), bufferBytes);

        if (nativePtr <= 0) {
            throw new LineUnavailableException("line with hwFormat " + hwFormat + " not supported.");
        }

        this.bufferBytes = SimpleMixer.nGetBufferBytes(nativePtr, isSource);
        if (this.bufferBytes < 1) {
            // this is an error!
            this.bufferBytes = bufferBytes;
        }
        // 1/8 of buffer time
        checkTimeMS = (int) ((long) this.bufferBytes / hwFormat.getFrameRate() * 1000.0f / hwFormat.getFrameSize()) / 8;
        bytePos = 0;
        writtenWhenStopped = false;
        inIO = false;
    }

    void doClose() {
        inIO = false;
        long prevID = nativePtr;
        nativePtr = 0;
        synchronized (lockNative) {
            SimpleMixer.nClose(prevID, isSource);
        }
        bytePos = 0;
    }

    private boolean isPCMEncoding(AudioFormat format) {
        return format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                || format.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED);
    }

    void doStart() {
        synchronized (lockNative) {
            SimpleMixer.nStart(nativePtr, isSource);
        }
        inIO = true;
        if (isSource && writtenWhenStopped) {
            setStarted(true);
            setActive(true);
        }
    }

    void doStop() {
        synchronized (lockNative) {
            SimpleMixer.nStop(nativePtr, isSource);
        }
        synchronized (lock) {
            inIO = false;
            lock.notifyAll();
        }
        setActive(false);
        setStarted(false);
        writtenWhenStopped = false;
    }

    @Override
    public int available() {
        if (nativePtr == 0)
            return 0;
        int a;
        synchronized (lockNative) {
            a = SimpleMixer.nGetAvailBytes(nativePtr, isSource);
        }
        logger.trace("Available: " + a + " bytes");
        return a;
    }

    @Override
    public void drain() {
        if (nativePtr != 0 && inIO) {
            synchronized (lockNative) {
                SimpleMixer.nDrain(nativePtr);
            }
        }
        drained = true;
    }

    @Override
    public void flush() {
        if (nativePtr != 0) {
            flushing = true;
            synchronized (lock) {
                lock.notifyAll();
            }
            synchronized (lockNative) {
                if (nativePtr != 0)
                    SimpleMixer.nFlush(nativePtr, isSource);
            }
            drained = true;
        }
    }

    @Override
    public long getLongFramePosition() {
        long pos;
        synchronized (lockNative) {
            pos = SimpleMixer.nGetBytePos(nativePtr, isSource, bytePos);
        }
        if (pos < 0)
            pos = 0;
        return (pos / getFormat().getFrameSize());
    }
}
