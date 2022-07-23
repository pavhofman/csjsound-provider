package com.cleansine.sound.provider;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

final class SimpleTargetDataLine extends SimpleDataLine implements TargetDataLine {

    SimpleTargetDataLine(DataLine.Info info, AudioFormat format, int bufferSize, SimpleMixer mixer) {
        super(info, mixer, format, bufferSize, mixer.getDeviceID(), false);
    }

    @Override
    public int read(byte[] bytes, int offset, int len) {
        flushing = false;
        if (len == 0)
            return 0;
        if (len % getFormat().getFrameSize() != 0)
            throw new IllegalArgumentException("Requesting to read non-integral number of frames (" + len + " bytes, " + "frameBytes = " + getFormat().getFrameSize() + " bytes)");
        if (!isActive() && inIO) {
            setActive(true);
            setStarted(true);
        }
        int read = 0;
        while (inIO && !flushing) {
            int thisRead;
            synchronized (lockNative) {
                thisRead = SimpleMixer.nRead(nativePtr, bytes, offset, len);
                if (thisRead < 0)
                    // error in native layer
                    break;
                bytePos += thisRead;
                if (thisRead > 0) {
                    drained = false;
                }
            }
            len -= thisRead;
            read += thisRead;
            if (len > 0) {
                offset += thisRead;
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
        if (flushing)
            read = 0;
        return read;
    }

}
