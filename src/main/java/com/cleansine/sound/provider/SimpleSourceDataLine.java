package com.cleansine.sound.provider;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

final class SimpleSourceDataLine extends SimpleDataLine implements SourceDataLine {

    SimpleSourceDataLine(DataLine.Info info, AudioFormat format, int bufferSize, SimpleMixer mixer) {
        super(info, mixer, format, bufferSize, mixer.getDeviceID(), true);
    }

    public int write(byte[] bytes, int offset, int len) {
        flushing = false;
        if (len == 0)
            return 0;
        if (len % getFormat().getFrameSize() != 0)
            throw new IllegalArgumentException("Requesting to write non-integral number of frames (" + len + " bytes, " + "frameBytes = " + getFormat().getFrameSize() + " bytes)");

        if (!isActive() && inIO) {
            setActive(true);
            setStarted(true);
        }
        int written = 0;
        while (!flushing) {
            int writtenInLoop;
            synchronized (lockNative) {
                writtenInLoop = SimpleMixer.nWrite(nativePtr, bytes, offset, len);
                if (writtenInLoop < 0)
                    // error in native layer
                    break;
                bytePos += writtenInLoop;
                if (writtenInLoop > 0)
                    drained = false;
            }
            len -= writtenInLoop;
            written += writtenInLoop;
            if (inIO && len > 0) {
                offset += writtenInLoop;
                synchronized (lock) {
                    try {
                        lock.wait(checkTimeMS);
                    } catch (InterruptedException ignored) {
                    }
                }
            } else {
                break;
            }
        }
        if (written > 0 && !inIO)
            writtenWhenStopped = true;
        return written;
    }

}
