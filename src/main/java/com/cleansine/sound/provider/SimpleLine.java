package com.cleansine.sound.provider;

import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;

abstract class SimpleLine implements Line {

    protected final Line.Info info;
    SimpleMixer mixer;
    private volatile boolean isOpen;

    protected SimpleLine(Line.Info info, SimpleMixer mixer) {
        this.info = info;
        this.mixer = mixer;
    }

    @Override
    public final Line.Info getLineInfo() {
        return info;
    }

    @Override
    public final boolean isOpen() {
        return isOpen;
    }

    final void setOpen(boolean open) {
        synchronized (this) {
            if (this.isOpen != open)
                this.isOpen = open;
        }
    }

    @Override
    public void addLineListener(LineListener listener) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public final void removeLineListener(LineListener listener) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public final Control[] getControls() {
        return new Control[0];
    }

    @Override
    public final boolean isControlSupported(Control.Type control) {
        return false;
    }

    @Override
    public final Control getControl(Control.Type control) {
        throw new IllegalArgumentException("No controls are available");
    }

    @Override
    public abstract void open() throws LineUnavailableException;

    @Override
    public abstract void close();
}
