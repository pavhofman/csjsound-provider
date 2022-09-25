# csjsound-provider
SPI javax.sound.sampled.spi.MixerProvider for csjsound dynamic libraries. Available native implementations:

* WASAPI exclusive https://github.com/pavhofman/csjsound-wasapi
* Linux ALSA PCM devices (as listed in aplay -L) https://github.com/pavhofman/csjsound-alsapcm


## Building

```
mvn package
```

The jar already includes the SPI service configuration in META-INF.

## Native Library Name and Location
The provider expects os-specific library name csjsound extended with os.arch https://github.com/pavhofman/csjsound-provider/blob/dbc56e987fc13539e997cd9305326105cf4f3618/src/main/java/com/cleansine/sound/provider/SimpleMixerProvider.java#L75  
Example: `csjsound_amd64.dll`, `libcsjsound_amd64.so`, `libcsjsound_aarch64.so`




The library location is specified by standard java property `-Djava.library.path`.

## Java Logs

The package uses slf4j API and slf4j-simple implementation, configurable via java properties. Example of settings:

```
-Dorg.slf4j.simpleLogger.defaultLogLevel=debug
-Dorg.slf4j.simpleLogger.logFile=System.out
-Dorg.slf4j.simpleLogger.showDateTime=True
-Dorg.slf4j.simpleLogger.showShortLogName=True
# this is important for time-aligning the java and DLL logs
-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS
```

## Native Library Logs
Logging paramaters are passed from the java provider to native library in native init method params `SimpleMixerProvider.nInit()`, read from java properties:
### csjsoundLibLogLevel
values: `trace`, `debug`, `info`, `warn`, `error`

If no `csjsoundLibLogLevel` is specified, the current SLF4J level is applied.

### csjsoundLibLogFile
values: `stdout`, `stderr`, path to a writable log_file

If no `csjsoundLibLogFile` is configured, the following default file is passed to the native library:
```
libLogTarget = System.getProperty("user.home") + "/csjsound-lib.log";
```

**Notes:**
- the ALSA-PCM implementation ignores this configuration and outputs to stdout at level defined in compile time
- the current WASAPI excl. implementation outputs to stdout even for `csjsoundLibLogFile=stderr`
