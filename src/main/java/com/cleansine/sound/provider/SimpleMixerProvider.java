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
    private static final int LIB_LOG_LEVEL_ERROR = 0;
    private static final int LIB_LOG_LEVEL_WARN = 1;
    private static final int LIB_LOG_LEVEL_INFO = 2;
    private static final int LIB_LOG_LEVEL_DEBUG = 3;
    private static final int LIB_LOG_LEVEL_TRACE = 4;

    // used if no property is specified
    private static final int[] DEFAULT_RATES = new int[]{44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000,
            705_600, 768_000, 1_411_200, 1_536_000};

    // used if no property is specified
    private static final int[] DEFAULT_CHANNELS = new int[]{1, 2, 4, 6, 8, 10, 12, 14, 16};

    // used if both DEFAULT_RATES and DEFAULT_CHANNELS are used
    // the rate/channels combination is applied IF rate < MAX_RATE_LIMIT || channels < MAX_CHANNELS_LIMIT
    private static final int MAX_RATE_LIMIT = 384000;
    private static final int MAX_CHANNELS_LIMIT = 8;

    // using static arrays to make sure GC does not drop the arrays before native processes them
    @SuppressWarnings("FieldCanBeLocal")
    private static int[] rates;
    @SuppressWarnings("FieldCanBeLocal")
    private static int[] channels;


    private static int[] parsePropertyToIntArray(String propertyStr) throws NumberFormatException {
        String str = System.getProperty(propertyStr);
        if (str != null && !str.isEmpty()) {
            String[] strItems = str.split(",");
            int[] items = new int[strItems.length];
            int idx = 0;
            try {
                for (String item : strItems) {
                    item = item.trim();
                    items[idx] = Integer.parseInt(item);
                    ++idx;
                }
            } catch (NumberFormatException e) {
                logger.error("Cannot parse to int array: " + str);
                return null;
            }
            return items.length > 0 ? items : null;
        } else
            return null;
    }


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


            boolean bothDefaults = true;
            rates = parsePropertyToIntArray("csjsoundRates");
            if (rates == null) {
                logger.info("No usable property csjsoundRates found, will use default rates: " + Arrays.toString(DEFAULT_RATES));
                rates = DEFAULT_RATES;
            } else {
                bothDefaults = false;
            }

            channels = parsePropertyToIntArray("csjsoundChannels");
            if (channels == null) {
                logger.info("No usable property csjsoundChannels found, will use default channels: " + Arrays.toString(DEFAULT_CHANNELS));
                channels = DEFAULT_CHANNELS;
            } else {
                bothDefaults = false;
            }

            if (bothDefaults)
                logger.info("Both default rates and default channels used, will use maximum combined limit: rate " + MAX_RATE_LIMIT + " vs. channels " + MAX_CHANNELS_LIMIT);


            logger.debug("Calling nInit with libLogTarget " + libLogTarget + ", rates: " + Arrays.toString(rates) + ", channels: " + Arrays.toString(channels));
            if (!nInit(libLogLevelID, libLogTarget, rates, channels, bothDefaults ? MAX_RATE_LIMIT : 0, bothDefaults ? MAX_CHANNELS_LIMIT : 0)) {
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
    private static native boolean nInit(int logLevelID, @Nonnull String logTarget, @Nonnull int[] rates, @Nonnull int[] channels, int maxRateLimit, int maxChannelsLimit);

    // count or -1 when error
    private static native int nGetMixerCnt();

    private static native SimpleMixerInfo nCreateMixerInfo(int idx);
}
