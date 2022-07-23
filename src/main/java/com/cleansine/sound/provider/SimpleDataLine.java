package com.cleansine.sound.provider;

import javax.annotation.Nonnull;
import javax.sound.sampled.*;

abstract class SimpleDataLine extends SimpleLine implements DataLine {
    protected static final int PCM_ENCODING = 0;
    private static final int DEFAULT_BUFFER_TIME_MS = 500;
    protected final String deviceID;
    protected final boolean isSource;
    protected AudioFormat format;
    protected int bufferSize;
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
    protected AudioFormat hwFormat;
    protected volatile boolean isStarted;
    protected volatile boolean isActive;

    protected SimpleDataLine(DataLine.Info info, SimpleMixer mixer, @Nonnull AudioFormat format, int bufferSize, String deviceID, boolean isSource) {
        super(info, mixer);
        this.format = format;
        this.bufferSize = bufferSize;
        this.deviceID = deviceID;
        this.checkTimeMS = 2;  // timeout to check whether all data have been read/written
        this.isSource = isSource;
    }


    public void open(@Nonnull AudioFormat format, int bufferSize) throws LineUnavailableException {
        //noinspection SynchronizeOnNonFinalField
        synchronized (mixer) {
            if (!isOpen()) {
                mixer.openLine(this);
                try {
                    doOpen(format, bufferSize);
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
        return bufferSize;
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
        open(format, bufferSize);
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
    }

    public int getFramePosition() {
        return (int) getLongFramePosition();
    }

    void doOpen(AudioFormat format, int bufferSize) throws LineUnavailableException {
        if (!isPCMEncoding(format))
            throw new IllegalArgumentException("Unsupported encoding " + format.getEncoding() + ", only PCM is supported");

        if (bufferSize <= AudioSystem.NOT_SPECIFIED) {
            // setting default value
            bufferSize = (int) ((long) DEFAULT_BUFFER_TIME_MS * format.getFrameRate() / 1000.0f * format.getFrameSize());
            // aligning to frames
            bufferSize = (bufferSize / format.getFrameSize()) * format.getFrameSize();
        }
        // aligning to frames
        bufferSize = (bufferSize / format.getFrameSize()) * format.getFrameSize();

        hwFormat = format;
        boolean isSigned = hwFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED);
        nativePtr = SimpleMixer.nOpen(deviceID, isSource, PCM_ENCODING, (int) hwFormat.getSampleRate(), hwFormat.getSampleSizeInBits(), hwFormat.getFrameSize(),
                hwFormat.getChannels(), isSigned, hwFormat.isBigEndian(), bufferSize);

        if (nativePtr == 0) {
            throw new LineUnavailableException("line with format " + format + " not supported.");
        }

        this.bufferSize = SimpleMixer.nGetBufferSize(nativePtr, isSource);
        if (this.bufferSize < 1) {
            // this is an error!
            this.bufferSize = bufferSize;
        }
        this.format = format;
        checkTimeMS = (int) ((long) this.bufferSize / format.getFrameRate() * 1000.0f / format.getFrameSize()) / 8;
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
        return a;
    }

    @Override
    public void drain() {
        if (nativePtr != 0   && inIO) {
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
