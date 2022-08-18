package com.cleansine.sound.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleMixerProvider extends MixerProvider {
    private static final Logger logger = LoggerFactory.getLogger(SimpleMixerProvider.class);
    private static final String LIBRARY_NAME = "csjsound";
    private static boolean isNativeLibLoaded;
    // all access synchronized, no need for concurrent version
    private static final Map<String, SimpleMixerInfo> infosByDeviceID = Collections.synchronizedMap(new LinkedHashMap<>());
    // all access synchronized, no need for concurrent version
    private static final Map<SimpleMixerInfo, SimpleMixer> mixersByInfo = new ConcurrentHashMap<>();

    // defined in the native LIB
    public static final int LIB_LOG_LEVEL_ERROR = 0;
    public static final int LIB_LOG_LEVEL_WARN = 1;
    public static final int LIB_LOG_LEVEL_INFO = 2;
    public static final int LIB_LOG_LEVEL_DEBUG = 3;
    public static final int LIB_LOG_LEVEL_TRACE = 4;

    static {
        isNativeLibLoaded = true;
        try {
            String lib = LIBRARY_NAME + "_" + System.getProperty("os.arch");
            logger.debug("Loading dynlib " + lib);
            System.loadLibrary(lib);
            String libLogLevel = System.getProperty("csjsoundLibLogLevel");
            Integer libLogLevelID = null;
            if (libLogLevel != null) {
                libLogLevel = libLogLevel.toLowerCase();
                switch (libLogLevel) {
                    case "error":
                        libLogLevelID = LIB_LOG_LEVEL_ERROR;
                        break;
                    case "warn":
                        libLogLevelID = LIB_LOG_LEVEL_WARN;
                        break;
                    case "info":
                        libLogLevelID = LIB_LOG_LEVEL_INFO;
                        break;
                    case "debug":
                        libLogLevelID = LIB_LOG_LEVEL_DEBUG;
                        break;
                    case "trace":
                        libLogLevelID = LIB_LOG_LEVEL_TRACE;
                        break;
                    default:
                        // will use java logger level
                        break;
                }
            }
            if (libLogLevelID == null) {
                // not requested, using java logger level
                if (logger.isTraceEnabled())
                    libLogLevelID = LIB_LOG_LEVEL_TRACE;
                else if (logger.isDebugEnabled())
                    libLogLevelID = LIB_LOG_LEVEL_DEBUG;
                else if (logger.isInfoEnabled())
                    libLogLevelID = LIB_LOG_LEVEL_INFO;
                else if (logger.isWarnEnabled())
                    libLogLevelID = LIB_LOG_LEVEL_WARN;
                else
                    libLogLevelID = LIB_LOG_LEVEL_DEBUG;
            }
            // csjsoundLibLogFile: either path to log file, or "stdout"/"stderr"
            String libLogTarget = System.getProperty("csjsoundLibLogFile");
            if (libLogTarget == null || libLogTarget.isEmpty())
                // directly to user home dir which should be writable
                libLogTarget = System.getProperty("user.home") + "/csjsound-lib.log";

            if (!nInit(libLogLevelID, libLogTarget)) {
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
        synchronized (SimpleMixerProvider.class) {
            int cnt = nGetMixerCnt();
            if (cnt < 0)
                // error
                return;
            if (infosByDeviceID.isEmpty() || cnt != infosByDeviceID.size()) {
                updateInfoCaches(cnt);
            }
        }
    }

    private static void updateInfoCaches(int cnt) {
        // keeping original infos and devices to avoid useless exceptions
        Collection<SimpleMixerInfo> origInfos = infosByDeviceID.values();
        HashMap<SimpleMixerInfo, SimpleMixer> mixersByInfoCopy = new HashMap<>(mixersByInfo);
        infosByDeviceID.clear();
        mixersByInfo.clear();
        for (int i = 0; i < cnt; i++) {
            SimpleMixerInfo newInfo = nCreateMixerInfo(i);
            logger.debug("Found device " + newInfo.toMyString());
            SimpleMixerInfo origInfo = findOrigInfo(newInfo, origInfos);
            if (origInfo != null) {
                // using the original info/device
                infosByDeviceID.put(origInfo.getDeviceID(), origInfo);
                SimpleMixer mixer = mixersByInfoCopy.get(origInfo);
                if (mixer != null)
                    mixersByInfo.put(origInfo, mixer);
            } else {
                infosByDeviceID.put(newInfo.getDeviceID(), newInfo);
            }
        }
    }

    @Nullable
    private static SimpleMixerInfo findOrigInfo(@Nonnull SimpleMixerInfo newInfo, @Nonnull Collection<SimpleMixerInfo> origInfos) {
        for (SimpleMixerInfo origInfo : origInfos) {
            if (newInfo.isEqualTo(origInfo))
                return origInfo;
        }
        return null;
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

    public static boolean isFullySpecifiedFormat(@Nonnull AudioFormat format) {
        return format.getSampleRate() != AudioSystem.NOT_SPECIFIED
                && format.getSampleSizeInBits() != AudioSystem.NOT_SPECIFIED
                && format.getChannels() != AudioSystem.NOT_SPECIFIED
                && format.getFrameSize() != AudioSystem.NOT_SPECIFIED;
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
    private static native boolean nInit(int logLevelID, @Nonnull String logTarget);

    // count or -1 when error
    private static native int nGetMixerCnt();

    private static native SimpleMixerInfo nCreateMixerInfo(int idx);
}
