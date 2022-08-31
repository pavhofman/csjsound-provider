package com.cleansine.sound.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;

/**
 * Class is basically a copy of the inner "Clip" class of DirectAudioDevice.java
 * form the openJDK project, modified to fit in with the "Simple" implementations
 *
 *
 * Note: as SimpleClip is ultimately based on SimpleLine it does not
 *       support "controls" nor does it support LineListener
 *
 */
class SimpleClip extends SimpleSourceDataLine
        implements Clip, Runnable {

    private static final long CLIP_BUFFER_TIME = 1000;
    private volatile Thread thread;
    private volatile byte[] audioData = null;
    private volatile int frameSize;         // size of one frame in bytes
    private volatile int m_lengthInFrames;
    private volatile int loopCount;
    private volatile int clipBytePosition;   // index in the audioData array at current playback
    private volatile int newFramePosition;   // set in setFramePosition()
    private volatile int loopStartFrame;
    private volatile int loopEndFrame;      // the last sample included in the loop


    SimpleClip(
            DataLine.Info info
            , AudioFormat format
            , int bufferSize
            , SimpleMixer mixer
            , Map<AudioFormat, AudioFormat> hwFormatByFormat
    ) {
        super(info, format, bufferSize, mixer, hwFormatByFormat);
    }

    // CLIP METHODS

    @Override
    public void open(AudioFormat format, byte[] data, int offset, int bufferSize)
            throws LineUnavailableException {

        // $$fb part of fix for 4679187: Clip.open() throws unexpected Exceptions
        isFullySpecifiedPCMFormat(format);
        if (bufferSize % format.getFrameSize() != 0) {
            String msg = String.format("Buffer size (%d) does not represent an integral number of sample frames (%d)"
                    , bufferSize
                    , format.getFrameSize()
            );
           throw new LineUnavailableException(msg);
        }

        byte[] newData = new byte[bufferSize];
        System.arraycopy(data, offset, newData, 0, bufferSize);
        open(format, newData, bufferSize / format.getFrameSize());
    }

    // this method does not copy the data array
    private void open(AudioFormat format, byte[] data, int frameLength)
            throws LineUnavailableException {

        // $$fb part of fix for 4679187: Clip.open() throws unexpected Exceptions
        isFullySpecifiedPCMFormat(format);

        synchronized (mixer) {
            if (isOpen()) {
                throw new IllegalStateException("Clip is already open with format " + getFormat() +
                        " and frame lengh of " + getFrameLength());
            } else {
                // if the line is not currently open, try to open it with this format and buffer size
                this.audioData = data;
                this.frameSize = format.getFrameSize();
                this.m_lengthInFrames = frameLength;
                // initialize loop selection with full range
                bytePos = 0;
                clipBytePosition = 0;
                newFramePosition = -1; // means: do not set to a new readFramePos
                loopStartFrame = 0;
                loopEndFrame = frameLength - 1;
                loopCount = 0; // means: play the clip irrespective of loop points from beginning to end

                try {
                    // use DirectDL's open method to open it
                    open(format, (int) millis2bytes(format, CLIP_BUFFER_TIME)); // one second buffer
                } catch (LineUnavailableException | IllegalArgumentException ex) {
                    audioData = null;
                    throw ex;
                }

                // if we got this far, we can instantiate the thread
                int priority = Thread.NORM_PRIORITY
                        + (Thread.MAX_PRIORITY - Thread.NORM_PRIORITY) / 3;
                thread = new Thread(this, "Simple Clip");
                thread.setDaemon(true);
                thread.setPriority(priority);
                // cannot start in createThread, because the thread
                // uses the "thread" variable as indicator if it should
                // continue to run
                thread.start();
            }
        }
    }

    @Override
    public void open(AudioInputStream stream) throws LineUnavailableException, IOException {

        // $$fb part of fix for 4679187: Clip.open() throws unexpected Exceptions
        isFullySpecifiedPCMFormat(stream.getFormat());

        synchronized (mixer) {
            byte[] streamData = null;

            if (isOpen()) {
                throw new IllegalStateException("Clip is already open with format " + getFormat() +
                        " and frame lengh of " + getFrameLength());
            }
            int lengthInFrames = (int)stream.getFrameLength();
            int bytesRead = 0;
            int frameSize = stream.getFormat().getFrameSize();
            if (lengthInFrames != AudioSystem.NOT_SPECIFIED) {
                // read the data from the stream into an array in one fell swoop.
                int arraysize = lengthInFrames * frameSize;
                if (arraysize < 0) {
                    throw new IllegalArgumentException("Audio data < 0");
                }
                try {
                    streamData = new byte[arraysize];
                } catch (OutOfMemoryError e) {
                    throw new IOException("Audio data is too big");
                }
                int bytesRemaining = arraysize;
                int thisRead = 0;
                while (bytesRemaining > 0 && thisRead >= 0) {
                    thisRead = stream.read(streamData, bytesRead, bytesRemaining);
                    if (thisRead > 0) {
                        bytesRead += thisRead;
                        bytesRemaining -= thisRead;
                    }
                    else if (thisRead == 0) {
                        Thread.yield();
                    }
                }
            } else {
                // read data from the stream until we reach the end of the stream
                // we use a slightly modified version of ByteArrayOutputStream
                // to get direct access to the byte array (we don't want a new array
                // to be allocated)
                int maxReadLimit = Math.max(16384, frameSize);
                DirectBAOS dbaos  = new DirectBAOS();
                byte[] tmp;
                try {
                    tmp = new byte[maxReadLimit];
                } catch (OutOfMemoryError e) {
                    throw new IOException("Audio data is too big");
                }
                int thisRead = 0;
                while (thisRead >= 0) {
                    thisRead = stream.read(tmp, 0, tmp.length);
                    if (thisRead > 0) {
                        dbaos.write(tmp, 0, thisRead);
                        bytesRead += thisRead;
                    }
                    else if (thisRead == 0) {
                        Thread.yield();
                    }
                } // while
                streamData = dbaos.getInternalBuffer();
            }
            lengthInFrames = bytesRead / frameSize;

            // now try to open the device
            open(stream.getFormat(), streamData, lengthInFrames);
        } // synchronized
    }

    @Override
    public int getFrameLength() {
        return m_lengthInFrames;
    }

    @Override
    public long getMicrosecondLength() {
        return frames2micros(getFormat(), getFrameLength());
    }

    @Override
    public void setFramePosition(int frames) {
        if (frames < 0) {
            frames = 0;
        }
        else if (frames >= getFrameLength()) {
            frames = getFrameLength();
        }
        if (inIO) {
            newFramePosition = frames;
        } else {
            clipBytePosition = frames * frameSize;
            newFramePosition = -1;
        }
        // fix for failing test050
        // $$fb although getFramePosition should return the number of rendered
        // frames, it is intuitive that setFramePosition will modify that
        // value.
        bytePos = frames * frameSize;

        // cease currently playing buffer
        flush();

    }

    // replacement for getFramePosition (see AbstractDataLine)
    @Override
    public long getLongFramePosition() {
        /* $$fb
         * this would be intuitive, but the definition of getFramePosition
         * is the number of frames rendered since opening the device...
         * That also means that setFramePosition() means something very
         * different from getFramePosition() for Clip.
         */
        // take into account the case that a new position was set...
        //if (!doIO && newFramePosition >= 0) {
        //return newFramePosition;
        //}
        return super.getLongFramePosition();
    }

    @Override
    public void setMicrosecondPosition(long microseconds) {
        long frames = micros2frames(getFormat(), microseconds);
        setFramePosition((int) frames);
    }

    @Override
    public void setLoopPoints(int start, int end) {
        if (start < 0 || start >= getFrameLength()) {
            throw new IllegalArgumentException("illegal value for start: "+start);
        }
        if (end >= getFrameLength()) {
            throw new IllegalArgumentException("illegal value for end: "+end);
        }

        if (end == -1) {
            end = getFrameLength() - 1;
            if (end < 0) {
                end = 0;
            }
        }

        // if the end position is less than the start position, throw IllegalArgumentException
        if (end < start) {
            throw new IllegalArgumentException("End position " + end + "  preceeds start position " + start);
        }

        // slight race condition with the run() method, but not a big problem
        loopStartFrame = start;
        loopEndFrame = end;
    }

    @Override
    public void loop(int count) {
        // note: when count reaches 0, it means that the entire clip
        // will be played, i.e. it will play past the loop end point
        loopCount = count;
        start();
    }


    // main playback loop
    @Override
    public void run() {
        Thread curThread = Thread.currentThread();
        while (thread == curThread) {
            // doIO is volatile, but we could check it, then get
            // pre-empted while another thread changes doIO and notifies,
            // before we wait (so we sleep in wait forever).
            synchronized(lock) {
                while (!inIO && thread == curThread) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            while (inIO && thread == curThread) {
                if (newFramePosition >= 0) {
                    clipBytePosition = newFramePosition * frameSize;
                    newFramePosition = -1;
                }
                int endFrame = getFrameLength() - 1;
                if (loopCount > 0 || loopCount == LOOP_CONTINUOUSLY) {
                    endFrame = loopEndFrame;
                }
                long framePos = (clipBytePosition / frameSize);
                int toWriteFrames = (int) (endFrame - framePos + 1);
                int toWriteBytes = toWriteFrames * frameSize;
                if (toWriteBytes > getBufferSize()) {
                    toWriteBytes = align(getBufferSize(), frameSize);
                }
                int written = write(audioData, clipBytePosition, toWriteBytes); // increases bytePosition
                clipBytePosition += written;
                // make sure nobody called setFramePosition, or stop() during the write() call
                if (inIO && newFramePosition < 0 && written >= 0) {
                    framePos = clipBytePosition / frameSize;
                    // since endFrame is the last frame to be played,
                    // framePos is after endFrame when all frames, including framePos,
                    // are played.
                    if (framePos > endFrame) {
                        // at end of playback. If looping is on, loop back to the beginning.
                        if (loopCount > 0 || loopCount == LOOP_CONTINUOUSLY) {
                            if (loopCount != LOOP_CONTINUOUSLY) {
                                loopCount--;
                            }
                            newFramePosition = loopStartFrame;
                        } else {
                            // no looping, stop playback
                            drain();
                            stop();
                        }
                    }
                }
            }
        }
    }

    // These methods copied/adapted from openjdk Toolkit.java

    void isFullySpecifiedPCMFormat(AudioFormat format) throws LineUnavailableException {
        if (!format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                && !format.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)) {
            throw new LineUnavailableException("SimpleClip must be PCM format");
        }
        if ((format.getFrameRate() <= 0)
                || (format.getSampleRate() <= 0)
                || (format.getSampleSizeInBits() <= 0)
                || (format.getFrameSize() <= 0)
                || (format.getChannels() <= 0)) {
            throw new LineUnavailableException("Frame rate, Sample rate, Sample size in bits, Frame size or Channel count not specified");
        }
    }

    long millis2bytes(AudioFormat format, long millis) {
        long result = (long) (millis * format.getFrameRate() / 1000.0f * format.getFrameSize());
        return align(result, format.getFrameSize());
    }

    long micros2frames(AudioFormat format, long micros) {
        return (long) (micros * format.getFrameRate() / 1000000.0f);
    }

    long frames2micros(AudioFormat format, long frames) {
        return (long) (((double) frames) / format.getFrameRate() * 1000000.0d);
    }

    long align(long bytes, int blockSize) {
        return blockSize <= 1 ? bytes :  bytes - (bytes % blockSize);
    }

    int align(int bytes, int blockSize) {
        return blockSize <= 1 ? bytes : bytes - (bytes % blockSize);
    }


    /*
     * private inner class representing a ByteArrayOutputStream
     * which allows retrieval of the internal array
     */
    private static class DirectBAOS extends ByteArrayOutputStream {
        DirectBAOS() {
            super();
        }

        public byte[] getInternalBuffer() {
            return buf;
        }

    } // class DirectBAOS
} // SimpleClip

