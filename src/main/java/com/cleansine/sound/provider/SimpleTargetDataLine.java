package com.cleansine.sound.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

final class SimpleTargetDataLine extends SimpleDataLine implements TargetDataLine {

    private static final Logger logger = LoggerFactory.getLogger(SimpleTargetDataLine.class);

    SimpleTargetDataLine(DataLine.Info info, AudioFormat format, int bufferSize, SimpleMixer mixer, boolean use24bits) {
        super(info, mixer, format, bufferSize, mixer.getDeviceID(), false, use24bits);
    }

    @Override
    public int read(byte[] bytes, int offset, int len) {
        synchronized (this) {
            flushing = false;
            if (len == 0)
                return 0;
            if (len % getFormat().getFrameSize() != 0)
                throw new IllegalArgumentException("Requesting to read non-integral number of frames (" + len + " bytes, " + "frameBytes = " + getFormat().getFrameSize() + " bytes)");
            if (!active && inIO) {
                setActive(true);
                setStarted(true);
            }
            int read = 0;
            while (inIO && !flushing) {
                int readInLoop;
                logger.trace("Trying to read " + len + " bytes");
                synchronized (lockNative) {
                    readInLoop = SimpleMixer.nRead(nativePtr, bytes, offset, len);
                    if (readInLoop < 0)
                        // error in native layer
                        break;
                    bytePos += readInLoop;
                    if (readInLoop > 0) {
                        drained = false;
                    }
                }
                logger.trace("Read " + readInLoop + " bytes");
                len -= readInLoop;
                read += readInLoop;
                if (len > 0) {
                    offset += readInLoop;
                    synchronized (lock) {
                        try {
                            logger.trace("Waiting in read loop for " + checkTimeMS + "ms");
                            lock.wait(checkTimeMS);
                        } catch (InterruptedException ignored) {
                        }
                    }
                } else {
                    break;
                }
            }
            if (flushing)
                read = 0;
            return read;
        }
    }

}
