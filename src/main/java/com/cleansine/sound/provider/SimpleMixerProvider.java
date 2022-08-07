package com.cleansine.sound.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SimpleMixerProvider extends MixerProvider {
    private static final Logger logger = LoggerFactory.getLogger(SimpleMixerProvider.class);
    private static final String LIBRARY_NAME = "csjsound";
    private static boolean isNativeLibLoaded;
    // all access synchronized, no need for concurrent version
    private static final Map<String, SimpleMixerInfo> infosByDeviceID = new LinkedHashMap<>();
    // all access synchronized, no need for concurrent version
    private static final Map<SimpleMixerInfo, SimpleMixer> mixersByInfo = new HashMap<>();

    static {
        isNativeLibLoaded = true;
        try {
            String lib = LIBRARY_NAME + "_" + System.getProperty("os.arch");
            logger.debug("Loading dynlib " + lib);
            System.loadLibrary(lib);
            if (!nInit()) {
                throw new Exception("Initializing " + lib + " failed");
            }
        } catch (Throwable t) {
            isNativeLibLoaded = false;
            logger.error("Error loading dynlib:" + t);
        }
    }

    public SimpleMixerProvider() {
        synchronized (SimpleMixerProvider.class) {
            if (isNativeLibLoaded)
                init();
        }
    }

    private static void init() {
        int cnt = nGetMixerCnt();
        if (cnt < 0)
            // error
            return;
        if (infosByDeviceID.isEmpty() || cnt != infosByDeviceID.size()) {
            infosByDeviceID.clear();
            mixersByInfo.clear();
            for (int i = 0; i < cnt; i++) {
                SimpleMixerInfo mInfo = nCreateMixerInfo(i);
                infosByDeviceID.put(mInfo.getDeviceID(), mInfo);
            }
        }
    }

    @Override
    public Mixer.Info[] getMixerInfo() {
        synchronized (SimpleMixerProvider.class) {
            return infosByDeviceID.values().toArray(new SimpleMixerInfo[0]);
        }
    }

    @Override
    public Mixer getMixer(@Nullable Mixer.Info info) {
        synchronized (SimpleMixerProvider.class) {
            if (info == null) {
                // get first mixer with nonzero source lines
                for (SimpleMixerInfo lInfo : infosByDeviceID.values()) {
                    Mixer mixer = getMixerFor(lInfo);
                    if (mixer.getSourceLineInfo().length > 0)
                        // found
                        return mixer;

                }
            }
            if (info instanceof SimpleMixerInfo)
                return getMixerFor((SimpleMixerInfo) info);
        }
        throw new IllegalArgumentException("Mixer " + info + "is not supported by this provider");
    }

    @Nonnull
    private static SimpleMixer getMixerFor(SimpleMixerInfo info) {
        SimpleMixer mixer = mixersByInfo.get(info);
        if (mixer == null) {
            mixer = new SimpleMixer(info);
            mixersByInfo.put(info, mixer);
        }
        return mixer;
    }

    /**
     * Must be called only once!
     */
    private static native boolean nInit();

    // count or -1 when error
    private static native int nGetMixerCnt();

    private static native SimpleMixerInfo nCreateMixerInfo(int idx);
}
