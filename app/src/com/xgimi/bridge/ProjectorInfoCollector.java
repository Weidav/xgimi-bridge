package com.xgimi.bridge;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Aggregates projector telemetry from:
 *   1. Standard Android TV APIs (DisplayManager, AudioManager, Settings, SystemProperties, Memory/Storage)
 *   2. com.xgimi.xgimiservice AIDL (IGimiVideo, IGimiAudio, IGimiProjector, IGimiCommon)
 *   3. xgimi.hardware.gmpf.* HAL services & dumpsys hdmi_control via shell-uid KeyDispatcher
 */
public class ProjectorInfoCollector {

    public static JSONObject collectAll(Context ctx, AidlClient aidl, LocalAdbClient adb) {
        long startMs = System.currentTimeMillis();
        JSONObject res = new JSONObject();

        // 1. Collect AIDL snapshot & extended getters
        JSONObject aidlJson = new JSONObject();
        try {
            Map<String, Object> aidlMap = aidl.collectAidlInfo();
            if (aidlMap != null) {
                aidlJson = JsonUtils.mapToJson(aidlMap);
            }
        } catch (Throwable ignore) {}

        // 2. Collect GMPF + CEC info via shell KeyDispatcher
        JSONObject gmpfJson = new JSONObject();
        String gmpfError = null;
        try {
            String rawHw = adb.queryHardwareInfo();
            if (rawHw != null && !rawHw.isEmpty()) {
                gmpfJson = new JSONObject(rawHw);
            }
        } catch (Throwable t) {
            gmpfError = t.getMessage();
        }

        // 3. Collect Android Framework, Settings & SystemProperties
        JSONObject fwDisplay = collectFrameworkDisplay(ctx);
        JSONObject fwAudio = collectFrameworkAudio(ctx);
        JSONObject settingsJson = collectSettings(ctx);
        JSONObject sysPropsJson = collectSysProps(aidl);
        JSONObject sysResJson = collectSystemResources(ctx);

        // Extract GMPF sub-objects
        JSONObject gTv = gmpfJson.optJSONObject("tv");
        JSONObject gDisp = gmpfJson.optJSONObject("display");
        JSONObject gAud = gmpfJson.optJSONObject("audio");
        JSONObject gTherm = gmpfJson.optJSONObject("thermal");
        JSONObject gFocus = gmpfJson.optJSONObject("focus");
        JSONObject gMotion = gmpfJson.optJSONObject("motion");
        JSONObject gSys = gmpfJson.optJSONObject("system");
        JSONObject gPwr = gmpfJson.optJSONObject("power");
        JSONObject gCfg = gmpfJson.optJSONObject("config");
        JSONObject gCec = gmpfJson.optJSONObject("cecDumpsys");
        JSONObject gMtkPq = gmpfJson.optJSONObject("mtkPq");
        if (gMtkPq == null) {
            gMtkPq = queryMtkPqFallback();
        }
        JSONObject mtkSettings = gMtkPq != null ? gMtkPq.optJSONObject("settings") : null;
        int mtkHdrType = gMtkPq != null ? gMtkPq.optInt("hdrType", -1) : -1;
        String mtkPicMode = mtkSettings != null ? mtkSettings.optString("Picture_Mode", null) : null;
        String tvSourcesStatus = settingsJson.optString("global.TvSourcesStatus", null);
        boolean isPlaybackRunning = "1".equals(sysPropsJson.optString("vendor.mtk.playback.running", "0"));
        String runningApp = DeviceState.getRunningApp(ctx);

        // Build categorized submenus + summary
        try {
            JSONObject summary = new JSONObject();
            JSONObject categories = new JSONObject();

            // --- Category 1: Signal & Display ---
            JSONObject catDisplay = new JSONObject();
            String inputSrcRaw = aidlJson.optString("currentInputSource", "");
            int gmpfInputSrc = gTv != null ? gTv.optInt("currentInputSource", -1) : -1;
            boolean isInternalStorage = "E_INPUT_SOURCE_STORAGE".equals(inputSrcRaw) || gmpfInputSrc == 34 || gmpfInputSrc == 0 || (tvSourcesStatus != null && tvSourcesStatus.startsWith("mm|"));
            String inputLabel = formatInputSource(inputSrcRaw, gmpfInputSrc);
            if (isInternalStorage && runningApp != null && !runningApp.isEmpty()) {
                inputLabel = "Android TV (" + runningApp + ")";
            }
            put(catDisplay, "activeInputSource", inputLabel);
            put(catDisplay, "rawInputSource", inputSrcRaw.isEmpty() ? gmpfInputSrc : inputSrcRaw);
            put(catDisplay, "runningApp", runningApp);
            put(catDisplay, "mediaCodecPlayback", isPlaybackRunning ? "Active (Decoding)" : "Idle");
            if (tvSourcesStatus != null && !tvSourcesStatus.isEmpty()) {
                put(catDisplay, "mediaCodecPipeline", tvSourcesStatus);
            }
            put(catDisplay, "hdmiPlayingState", "1".equals(sysPropsJson.optString("vendor.xgimi.hdmi.playing.state", "0")) ? "Playing (1)" : "Idle (0)");

            JSONObject hdmiInfo = gTv != null ? gTv.optJSONObject("hdmiInfo") : null;
            JSONObject videoInfo = gTv != null ? gTv.optJSONObject("videoInfo") : null;
            JSONObject outTiming = gDisp != null ? gDisp.optJSONObject("outputTiming") : null;

            String signalRes = null;
            if (hdmiInfo != null && hdmiInfo.optInt("width", 0) > 0) {
                int w = hdmiInfo.optInt("width");
                int h = hdmiInfo.optInt("height");
                int fr = hdmiInfo.optInt("frameRate");
                boolean interlace = hdmiInfo.optBoolean("interlace", false);
                double fps = fr > 1000 ? (fr / 1000.0) : (fr > 200 ? fr / 10.0 : fr);
                signalRes = w + "x" + h + (interlace ? "i" : "p") + (fps > 0 ? " @ " + fmtDouble(fps) + " Hz" : "");
                put(catDisplay, "hdmiSignalTiming", signalRes);
                put(catDisplay, "hdmiMode", hdmiInfo.optBoolean("isHdmiMode", true) ? "HDMI" : "DVI");
            } else if (videoInfo != null && videoInfo.optInt("width", 0) > 0) {
                int w = videoInfo.optInt("width");
                int h = videoInfo.optInt("height");
                int fr = videoInfo.optInt("frameRate");
                double fps = fr > 1000 ? (fr / 1000.0) : (fr > 200 ? fr / 10.0 : fr);
                signalRes = w + "x" + h + (fps > 0 ? " @ " + fmtDouble(fps) + " Hz" : "");
                put(catDisplay, "videoTiming", signalRes);
            }

            String activeDisplayMode = fwDisplay.optString("activeMode", null);
            put(catDisplay, "androidDisplayMode", activeDisplayMode);
            put(catDisplay, "panelOutputResolution", sysPropsJson.optString("vendor.display-size", null));
            put(catDisplay, "osdUiRenderResolution", sysPropsJson.optString("vendor.mtk.osd_size", null));
            if (outTiming != null && outTiming.optInt("dispWidth", 0) > 0) {
                put(catDisplay, "dlpOutputTiming",
                        outTiming.optInt("dispWidth") + "x" + outTiming.optInt("dispHeight")
                                + " @ " + outTiming.optInt("vfreq") + " Hz"
                                + " (src " + outTiming.optInt("currHres") + "x" + outTiming.optInt("currVres") + ")");
            }
            if (gDisp != null && gDisp.optInt("dispOutputFps", 0) > 0) {
                put(catDisplay, "dlpOutputFps", gDisp.optInt("dispOutputFps") + " Hz");
            }
            if (gDisp != null && gDisp.has("dlpXprMode")) {
                put(catDisplay, "dlpXprPixelShift", gDisp.optBoolean("dlpXprMode") ? "Enabled (4K XPR)" : "Off (Native)");
            }

            // HDR / Dolby Vision / IMAX / Filmmaker
            int hdrType = gTv != null ? gTv.optInt("hdrType", 0) : 0;
            boolean isPlayingHdr = (gDisp != null && gDisp.optBoolean("isPlayingHdrVideo", false))
                    || hdrType > 0
                    || (tvSourcesStatus != null && !tvSourcesStatus.toUpperCase().contains("SDR"));
            boolean hdrSupportEnabled = (gTv != null && gTv.optBoolean("hdrEnable", true))
                    || aidlJson.optBoolean("hdrEnable", true);
            String hdrLabel = resolveHdrStatus(hdrType, isPlayingHdr, gTv, tvSourcesStatus, mtkHdrType, mtkPicMode, isInternalStorage);
            put(catDisplay, "hdrStatus", hdrLabel);
            put(catDisplay, "hdrSupportEnabled", hdrSupportEnabled);
            put(catDisplay, "hdrSettingMode", settingsJson.optString("global.picture_list_hdr", null));

            String dvPicModeLabel;
            if (mtkPicMode != null && mtkPicMode.toLowerCase().contains("dolbyvision")) {
                dvPicModeLabel = formatMtkPictureMode(mtkPicMode);
            } else if (gTv != null && gTv.has("dolbyVisionPicMode") && gTv.optInt("dolbyVisionPicMode", -1) > 0) {
                dvPicModeLabel = formatDolbyVisionMode(gTv.optInt("dolbyVisionPicMode"));
            } else if (hdrLabel.contains("Dolby Vision")) {
                dvPicModeLabel = "Active";
            } else {
                dvPicModeLabel = "Inactive (0)";
            }
            put(catDisplay, "dolbyVisionPicMode", dvPicModeLabel);

            boolean isFfmPlaying = "FilmMaker".equalsIgnoreCase(mtkPicMode) || (gTv != null && gTv.optBoolean("isFFMPlaying", false));
            boolean isImaxPlaying = "ImaxEnhanced".equalsIgnoreCase(mtkPicMode) || (gTv != null && gTv.optBoolean("isImaxVideoPlaying", false));
            boolean isHdrVividPlaying = (tvSourcesStatus != null && tvSourcesStatus.toUpperCase().contains("HDRVIVID"))
                    || (gTv != null && gTv.optBoolean("isHdrVividPlaying", false));
            put(catDisplay, "filmmakerModePlaying", isFfmPlaying);
            put(catDisplay, "hdrVividPlaying", isHdrVividPlaying);
            put(catDisplay, "imaxEnhancedPlaying", isImaxPlaying);
            put(catDisplay, "supportedHdrFormats", fwDisplay.optString("supportedHdrTypes", null));
            if (fwDisplay.has("hdrMaxLuminance")) {
                put(catDisplay, "panelHdrLuminanceRange",
                        fwDisplay.optDouble("hdrMinLuminance", 0) + " – "
                                + fwDisplay.optDouble("hdrMaxLuminance", 0)
                                + " nits (avg " + fwDisplay.optDouble("hdrMaxAvgLuminance", 0) + " nits)");
            }

            // Gaming & Motion (ALLM, VRR, FreeSync, HGiG, MEMC)
            int gameMode = aidlJson.optInt("gameMode", -1);
            put(catDisplay, "gameLowLatencyMode", formatGameMode(gameMode));
            put(catDisplay, "gamePictureMode", settingsJson.optInt("global.game_picture_mode", -1));
            if (gTv != null) {
                put(catDisplay, "allmEnabled", gTv.optBoolean("allmEnable", false));
                put(catDisplay, "allmActive", gTv.optBoolean("allmStatus", false));
                put(catDisplay, "vrrState", gTv.optInt("vrrEnableState", 0) == 0 ? "Off (0)" : "Active (" + gTv.optInt("vrrEnableState") + ")");
                put(catDisplay, "amdFreeSync", gTv.optBoolean("amdFreeSyncState", false));
                put(catDisplay, "hgigMode", gTv.optBoolean("hgigMode", false));
            } else {
                put(catDisplay, "hgigStatusProp", sysPropsJson.optString("persist.vendor.xgimi.system.hgig.status", null));
            }
            int memc = aidlJson.optInt("mfcLevel", gTv != null ? gTv.optInt("memcLevel", -1) : -1);
            put(catDisplay, "memcMotionCompensation", formatMemcLevel(memc));

            // Color Space, Range, Aspect Ratio, EDID, 3D
            int colorSpaceSetting = settingsJson.optInt("global.gm_color_space", -1);
            int dispColorSpace = gDisp != null ? gDisp.optInt("dispColorSpace", -1) : -1;
            put(catDisplay, "colorSpace", formatColorSpace(colorSpaceSetting >= 0 ? colorSpaceSetting : dispColorSpace));
            if (gTv != null && gTv.has("hdmiRange")) {
                put(catDisplay, "hdmiColorRange", formatHdmiRange(gTv.optInt("hdmiRange", 0)));
            }
            int aspect = aidlJson.optInt("aspectRatio", gTv != null ? gTv.optInt("aspectRatio", -1) : -1);
            put(catDisplay, "aspectRatio", formatAspectRatio(aspect));
            int edidVer = aidlJson.optInt("edidVersion", gTv != null ? gTv.optInt("edidVersion", -1) : -1);
            put(catDisplay, "edidVersion", formatEdidVersion(edidVer));
            int cur3d = aidlJson.optInt("current3dType", -1);
            int disp3d = gDisp != null ? gDisp.optInt("current3dFormat", 0) : 0;
            put(catDisplay, "mode3d", format3dMode(cur3d >= 0 ? cur3d : disp3d, aidlJson.optBoolean("is3dTo2d", false)));
            put(catDisplay, "supportedDisplayModes", fwDisplay.optJSONArray("supportedModes"));

            categories.put("display", catDisplay);

            // --- Category 2: Picture & Brightness ---
            JSONObject catBrightness = new JSONObject();
            int gmBrightness = settingsJson.optInt("global.gm_projector_brightness", -1);
            int dlpLumensLvl = gDisp != null ? gDisp.optInt("dlpLumensLevel", -1) : -1;
            int dlpLumensMode = gDisp != null ? gDisp.optInt("dlpLumensMode", -1) : -1;

            String luminanceModeLabel = formatLuminanceMode(dlpLumensMode, gmBrightness);
            put(catBrightness, "luminancePowerMode", luminanceModeLabel);
            if (dlpLumensLvl > 0) {
                put(catBrightness, "brightnessLevel", dlpLumensLvl + " / 10");
            } else if (gmBrightness >= 1 && gmBrightness <= 10) {
                put(catBrightness, "brightnessLevel", gmBrightness + " / 10");
            }
            put(catBrightness, "androidBacklightValue", settingsJson.optInt("system.screen_brightness", -1));

            // Lamp state: vendor.xgimi.ledOn ("true"/"false") and DisplayManagerService.getScreenOnOff() are authoritative on laser models
            Boolean ledOn = null;
            if (sysPropsJson.has("vendor.xgimi.ledOn")) {
                ledOn = "true".equalsIgnoreCase(sysPropsJson.optString("vendor.xgimi.ledOn"));
            } else if (gDisp != null && gDisp.has("screenOn")) {
                ledOn = gDisp.optBoolean("screenOn", true);
            } else if (aidlJson.has("ledOn")) {
                ledOn = aidlJson.optBoolean("ledOn");
            }
            put(catBrightness, "lightSourcePower", ledOn != null ? (ledOn ? "On (Active)" : "Off (Lamp Disabled)") : null);

            if (gDisp != null) {
                put(catBrightness, "dualLightMode", gDisp.optInt("dualLightMode", 0) == 0 ? "Dual Light / Standard (0)" : "Mode " + gDisp.optInt("dualLightMode"));
                JSONObject rgb = gDisp.optJSONObject("rgbLevels");
                if (rgb != null) {
                    put(catBrightness, "lightSourceRgbLevels",
                            "R: " + rgb.optInt("r") + " / G: " + rgb.optInt("g") + " / B: " + rgb.optInt("b")
                                    + (rgb.optInt("c", 0) != 0 ? " / C: " + rgb.optInt("c") : ""));
                    put(catBrightness, "rgbLevelsRaw", rgb);
                }
            }
            boolean turboLight = (gFocus != null && gFocus.optBoolean("turboLightOn", false))
                    || settingsJson.optInt("system.smart_turbo_light_enabled", 0) == 1;
            put(catBrightness, "smartTurboLight", turboLight);
            put(catBrightness, "advancedPerformanceMode", settingsJson.optInt("global.picture_advanced_performance_mode", 0) == 1);
            put(catBrightness, "pqPerformanceActive", "1".equals(sysPropsJson.optString("sys.xgimi.pqmode.isperf", "0")));

            // Picture Mode & PQ sliders
            int picMode = aidlJson.optInt("pictureMode", -1);
            String picModeLabel = resolvePictureMode(picMode, mtkPicMode);
            put(catBrightness, "pictureMode", picModeLabel);

            if (mtkSettings != null) {
                put(catBrightness, "pqBrightness", mtkSettings.has("Brightness") ? mtkSettings.opt("Brightness") : aidlJson.opt("brightness"));
                put(catBrightness, "pqContrast", mtkSettings.has("Contrast") ? mtkSettings.opt("Contrast") : aidlJson.opt("contrast"));
                put(catBrightness, "pqSaturation", mtkSettings.has("Saturation") ? mtkSettings.opt("Saturation") : aidlJson.opt("saturation"));
                put(catBrightness, "pqSharpness", mtkSettings.has("SR_Strength") ? mtkSettings.opt("SR_Strength") : aidlJson.opt("sharpness"));
                put(catBrightness, "pqHue", mtkSettings.has("Hue") ? mtkSettings.opt("Hue") : aidlJson.opt("hue"));

                int colorTemp = aidlJson.optInt("colorTemp", -1);
                String mtkColorTemp = mtkSettings.optString("Color_Temperature", null);
                put(catBrightness, "colorTemperatureMode", mtkColorTemp != null ? formatMtkColorTemp(mtkColorTemp) : formatColorTemp(colorTemp));

                if (mtkSettings.has("MJC_Deblur")) put(catBrightness, "memcDeblur", mtkSettings.optInt("MJC_Deblur"));
                if (mtkSettings.has("MJC_Dejudder")) put(catBrightness, "memcDejudder", mtkSettings.optInt("MJC_Dejudder"));
                if (mtkSettings.has("Global_Dimming")) put(catBrightness, "globalDimming", mtkSettings.optString("Global_Dimming"));
                if (mtkSettings.has("Dark_Detail")) put(catBrightness, "darkDetail", mtkSettings.optString("Dark_Detail"));
                if (mtkSettings.has("Low_Blue_Light")) put(catBrightness, "lowBlueLight", mtkSettings.optString("Low_Blue_Light"));
                if (mtkSettings.has("Film_Mode")) put(catBrightness, "filmMode", mtkSettings.optString("Film_Mode"));
            } else {
                put(catBrightness, "pqBrightness", aidlJson.opt("brightness"));
                put(catBrightness, "pqContrast", aidlJson.opt("contrast"));
                put(catBrightness, "pqSaturation", aidlJson.opt("saturation"));
                put(catBrightness, "pqSharpness", aidlJson.opt("sharpness"));
                put(catBrightness, "pqHue", aidlJson.opt("hue"));

                int colorTemp = aidlJson.optInt("colorTemp", -1);
                put(catBrightness, "colorTemperatureMode", formatColorTemp(colorTemp));
            }

            if (aidlJson.has("wbRed")) {
                put(catBrightness, "whiteBalanceGains",
                        "R: " + aidlJson.optInt("wbRed") + " / G: " + aidlJson.optInt("wbGreen") + " / B: " + aidlJson.optInt("wbBlue"));
            }

            // Contrast, Black Level, Enhancements
            if (gDisp != null) {
                put(catBrightness, "dynamicBlackLevel", formatOffLowMedHigh(gDisp.optInt("dynamicBlackLevel", -1)));
                put(catBrightness, "contrastEnhanceLevel", formatOffLowMedHigh(gDisp.optInt("contrastEnhanceLvl", -1)));
                if (gDisp.optInt("colorEnhance", -1) >= 0) {
                    put(catBrightness, "colorEnhance", gDisp.optInt("colorEnhance"));
                }
                put(catBrightness, "rainbowEffectReduction",
                        gDisp.optInt("rainbowReductionStatus", 0) == 0 ? "Off (0)"
                                : "Enabled (Level " + gDisp.optInt("rainbowReductionLevel", 0) + ")");
            }
            put(catBrightness, "colorEnhancingFilter", settingsJson.optInt("global.gm_color_enhancing_filter", 0) == 1);
            put(catBrightness, "noiseReduction", formatOffLowMedHigh(aidlJson.optInt("noiseReduction", -1)));
            if (gTv != null) {
                put(catBrightness, "dynamicContrastEnable", gTv.optBoolean("dynamicContrastEnable", false));
                put(catBrightness, "gammaLevel", gTv.optInt("gammaLevel", 0));
                put(catBrightness, "aiPqEnabled", gTv.optBoolean("aiPqEnable", false));
                put(catBrightness, "aisrSuperResolution",
                        gTv.optInt("aisrLevel", 0) == 0 ? "Off (0)" : "Level " + gTv.optInt("aisrLevel", 0));
            }

            categories.put("brightness_picture", catBrightness);

            // --- Category 3: Audio & Pass-Through ---
            JSONObject catAudio = new JSONObject();
            int gmpfAudioOut = gAud != null ? gAud.optInt("audioOutput", -1) : -1;
            int aidlAudioOut = aidlJson.optInt("audioOutputDevices", -1);
            String activeAudioRoute = fwAudio.optString("activeOutputRoute", null);
            String audioOutLabel = formatAudioOutputDevice(gmpfAudioOut, aidlAudioOut, activeAudioRoute);
            put(catAudio, "activeOutputDevice", audioOutLabel);
            put(catAudio, "androidOutputDevices", fwAudio.optJSONArray("outputDevices"));

            String digOutFmtProp = sysPropsJson.optString("vendor.xgimi.system.audio.digital_output_format", null);
            int aidlDataFmt = aidlJson.optInt("audioDataFormat", -1);
            int gmpfArcType = gAud != null ? gAud.optInt("arcType", -1) : -1;
            int gmpfSpdifType = gAud != null ? gAud.optInt("spdifType", -1) : -1;
            int spdifSetting = settingsJson.optInt("system.spdif_mode", -1);

            String digitalAudioFormatLabel = formatDigitalAudioFormat(digOutFmtProp, aidlDataFmt, gmpfArcType);
            put(catAudio, "digitalOutputPassThroughFormat", digitalAudioFormatLabel);
            put(catAudio, "rawDigitalOutputProperty", digOutFmtProp);
            put(catAudio, "arcOutputFormat", formatArcType(gmpfArcType));
            put(catAudio, "spdifOutputFormat", formatSpdifType(gmpfSpdifType, spdifSetting));

            int earcDumpsys = gCec != null ? gCec.optInt("earcDumpsysStatus", -1) : -1;
            int earcTv = gTv != null ? gTv.optInt("earcStatus", -1) : -1;
            int earcStatus = earcDumpsys > 0 ? earcDumpsys : earcTv;
            boolean earcEnabled = (gTv != null && gTv.optBoolean("earcEnableState", false)) || earcStatus == 3;
            String earcHandshakeLabel = formatEarcStatus(earcStatus);
            put(catAudio, "earcEnabled", earcEnabled);
            put(catAudio, "earcHandshakeStatus", earcHandshakeLabel);
            put(catAudio, "externalSurroundSoundEnabled", settingsJson.optInt("global.nrdp_external_surround_sound_enabled", 0) == 1);

            int soundEffect = gAud != null ? gAud.optInt("soundEffect", -1) : aidlJson.optInt("soundMode", -1);
            int soundProcessType = gAud != null ? gAud.optInt("soundProcessType", -1) : -1;
            put(catAudio, "soundMode", formatSoundMode(soundEffect));
            put(catAudio, "soundProcessorType", formatSoundProcessType(soundProcessType));

            if (gAud != null) {
                put(catAudio, "dolbySurround", gAud.optBoolean("dolbySurroundEnable", false));
                put(catAudio, "dolbyVirtualSurround", gAud.optBoolean("dolbySurroundVirtual", false));
                put(catAudio, "dolbyDialogEnhancer", formatOffLowMedHigh(gAud.optInt("dolbyDialogEnhancer", 0)));
                put(catAudio, "dolbyVolumeLeveler", gAud.optBoolean("dolbyVolumeLeveler", false));
                put(catAudio, "dtsEffectsEnabled", gAud.optBoolean("dtsEffectsEnable", false));
                put(catAudio, "dtsTruBass", gAud.optBoolean("dtsTruBassEnable", false));
                put(catAudio, "dtsTruSurround", gAud.optBoolean("dtsTruSurroundEnable", false));
                put(catAudio, "dtsTruVolume", gAud.optBoolean("dtsTruVolumeEnable", false));
                put(catAudio, "dtsDialogClarity", gAud.optBoolean("dtsDialogClarityEnable", false));
                put(catAudio, "dtsDrc", gAud.optInt("dtsDrc", 0));
                put(catAudio, "syncImageSoundEffects", gAud.optBoolean("syncImageSoundEffects", false));
                put(catAudio, "volumeBalanceEnabled", gAud.optBoolean("volumeBalanceEnable", false));
                put(catAudio, "audioDelayMs", gAud.optInt("audioDelay", 0) + " ms");
                put(catAudio, "videoDelayMs", gAud.optInt("videoDelay", 0) + " ms");
                put(catAudio, "amplifierFirmwareVersion", gAud.optString("ampVersion", null));
            }
            put(catAudio, "musicVolumeCurrent", fwAudio.optInt("musicVolume", -1) + " / " + fwAudio.optInt("musicMaxVolume", -1));
            put(catAudio, "musicMuted", fwAudio.optBoolean("musicMuted", false));
            put(catAudio, "speakerVolumeSetting", settingsJson.optInt("system.volume_music_speaker", -1));
            put(catAudio, "hdmiArcVolumeSetting", settingsJson.optInt("system.volume_music_hdmi_arc", -1));
            put(catAudio, "hdmiEarcVolumeSetting", settingsJson.optInt("system.volume_music_hdmi_earc", -1));

            categories.put("audio", catAudio);

            // --- Category 4: Optics, Geometry & Sensors ---
            JSONObject catOptics = new JSONObject();
            int putMode = gDisp != null ? gDisp.optInt("projectorPutMode", -1) : -1;
            put(catOptics, "projectionPlacementMode", formatProjectorPutMode(putMode));
            if (gDisp != null) {
                put(catOptics, "keystoneMode", formatKeystoneMode(gDisp.optInt("currentKeystoneMode", -1)));
                JSONObject zs = gDisp.optJSONObject("zoomStepDetail");
                if (zs != null) {
                    put(catOptics, "opticalZoomStep", zs.optInt("opticalStep", 0));
                    put(catOptics, "digitalZoomStep", zs.optInt("digitalStep", 0));
                } else {
                    put(catOptics, "zoomStep", gDisp.optInt("currentZoomStep", 0));
                }
                put(catOptics, "ambientLightAdaptation", gDisp.optBoolean("ambientLightAdaptOn", false));
                put(catOptics, "eyeProtectionHumanDetect", gDisp.optBoolean("humanDetectOn", false));
            }
            if (gFocus != null) {
                put(catOptics, "focusMotorCurrentStep", gFocus.optInt("focusMotorCurrentStep", 0));
                put(catOptics, "focusMotorSpeedPps", gFocus.optInt("focusPps", 0) + " PPS");
                put(catOptics, "zoomMotorSpeedPps", gFocus.optInt("zoomPps", 0) + " PPS");
                int tofMm = gFocus.optInt("tofDistance", -1);
                if (tofMm >= 0) {
                    put(catOptics, "tofDistanceToWall", tofMm + " mm (" + fmtDouble(tofMm / 1000.0) + " m)");
                }
                put(catOptics, "ambientLightSensor", gFocus.optInt("ambientLight", -1) + " lux");
                put(catOptics, "autoFocusOnPowerOn", gFocus.optBoolean("powerOnAfEnabled", false));
                put(catOptics, "thermalFocusAutoCompensate", gFocus.optBoolean("focusAutoCompensate", false));
                put(catOptics, "autoScreenAlignment", gFocus.optBoolean("autoScreenAlign", false));
                put(catOptics, "smartObstacleAvoidance", gFocus.optBoolean("obstacleAvoidance", false));
                put(catOptics, "autoWallColorAdapt", gFocus.optBoolean("autoColorAdapt", false));
                String tsInfo = gFocus.optString("tiltShiftCurPositionInfo", "");
                if (!tsInfo.isEmpty()) put(catOptics, "lensShiftPositionInfo", tsInfo);
            }
            if (gMotion != null) {
                if (gMotion.has("pitch") || gMotion.has("roll")) {
                    put(catOptics, "imuPitchRollAngles",
                            "Pitch: " + gMotion.optDouble("pitch", 0) + "° / Roll: " + gMotion.optDouble("roll", 0) + "°");
                }
                put(catOptics, "motionTriggeredAutoKeystone", gMotion.optBoolean("accTriggerAutoKeystone", false));
                put(catOptics, "motionTriggeredAutoFocus", gMotion.optBoolean("angTriggerAutoFocus", false));
                put(catOptics, "autoCeilingReverse", gMotion.optBoolean("autoReverse", false));
            }
            if (gSys != null) {
                if (gSys.has("standVerticalAngle")) {
                    put(catOptics, "standVerticalAngle", gSys.optDouble("standVerticalAngle", 0) + "°");
                }
                if (gSys.has("pantiltVerticalAng")) {
                    put(catOptics, "gimbalPanTiltRoll",
                            "Pan: " + gSys.optDouble("pantiltHorizontalAng", 0) + "° / Tilt: "
                                    + gSys.optDouble("pantiltVerticalAng", 0) + "° / Roll: "
                                    + gSys.optDouble("pantiltRollingAng", 0) + "°");
                }
                if (gSys.optInt("shiftMaxStep", 0) > 0) {
                    put(catOptics, "lensShiftStep", gSys.optInt("shiftCurrentStep", 0) + " / " + gSys.optInt("shiftMaxStep", 0));
                }
                put(catOptics, "radarDetectionEnabled", gSys.optBoolean("radarDetectOn", false));
            }

            categories.put("optics_sensors", catOptics);

            // --- Category 5: Thermal, Cooling & Power ---
            JSONObject catThermal = new JSONObject();
            int sysTempC = gTherm != null ? gTherm.optInt("systemTemperature", -1) : -1;
            if (sysTempC >= 0) {
                put(catThermal, "systemTemperature", sysTempC + " °C");
            }
            if (gTherm != null) {
                put(catThermal, "overheatWarning", gTherm.optBoolean("isTempUltraHigh", false));
                int hum = gTherm.optInt("humidity", -1);
                if (hum > 0 && hum <= 100) put(catThermal, "humidity", hum + "%");
                put(catThermal, "fanControlMode", gTherm.optInt("fanMode", 0));
                put(catThermal, "standFanMode", gTherm.optBoolean("fanStandMode", false));
                int pumpRpm = gTherm.optInt("pumpRpm", -1);
                if (pumpRpm > 0) put(catThermal, "coolingPumpRpm", pumpRpm + " RPM");
                int tecSpeed = gTherm.optInt("tecSpeed", -1);
                if (tecSpeed > 0) put(catThermal, "tecPeltierSpeed", tecSpeed);

                JSONArray sensors = gTherm.optJSONArray("sensors");
                if (sensors != null && sensors.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < sensors.length(); i++) {
                        JSONObject s = sensors.optJSONObject(i);
                        if (s != null) {
                            if (sb.length() > 0) sb.append(" • ");
                            String name = s.optString("name", "Sensor " + s.optInt("index"));
                            sb.append(name).append(": ").append(s.opt("tempC")).append("°C");
                        }
                    }
                    put(catThermal, "temperatureSensorsSummary", sb.toString());
                    put(catThermal, "temperatureSensors", sensors);
                }

                JSONArray fans = gTherm.optJSONArray("fans");
                if (fans != null && fans.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < fans.length(); i++) {
                        JSONObject f = fans.optJSONObject(i);
                        if (f != null) {
                            if (sb.length() > 0) sb.append(" • ");
                            int val = f.optInt("rpm");
                            String unit = val <= 100 ? "% duty" : " RPM";
                            sb.append("Fan ").append(f.optInt("index") + 1).append(": ").append(val).append(unit);
                        }
                    }
                    put(catThermal, "fanSpeedsSummary", sb.toString());
                    put(catThermal, "fans", fans);
                }
            }
            if (gPwr != null) {
                double volts = gPwr.optDouble("powerSupplyVoltage", -1);
                if (volts > 0) {
                    double v = volts > 100 ? (volts / 1000.0) : volts;
                    put(catThermal, "powerSupplyVoltage", fmtDouble(v) + " V");
                }
                put(catThermal, "powerMode", gPwr.optInt("powerMode", 0));
                put(catThermal, "standbyStage", gPwr.optInt("standbyStage", 0));
                put(catThermal, "adapterPowered", gPwr.optBoolean("isAdapterPowered", true));
                put(catThermal, "autoEcoMode", gPwr.optBoolean("autoEcoMode", false));
            }
            if (gSys != null) {
                put(catThermal, "ecoState", gSys.optBoolean("ecoState", false));
                put(catThermal, "highAltitudeMode", gSys.optBoolean("highAltitudeMode", false));
                put(catThermal, "silentMode", gSys.optBoolean("silentMode", false));
                int usageMinOrHr = gSys.optInt("machineUsageTime", -1);
                if (usageMinOrHr > 0) {
                    put(catThermal, "machineUsageTime", usageMinOrHr + " hrs");
                }
            }
            if (gDisp != null) {
                String lightUsage = gDisp.optString("lightUsageInfo", "");
                if (!lightUsage.isEmpty()) put(catThermal, "lightSourceUsageInfo", lightUsage);
            }
            put(catThermal, "lowPowerStandbyEnabled", settingsJson.optInt("global.low_power_standby_enabled", 0) == 1);
            put(catThermal, "wakeOnLanWow", sysPropsJson.optString("vendor.xgimi.wow_value",
                    sysPropsJson.optString("persist.vendor.xgimi.system.wow", null)));

            categories.put("thermal_power", catThermal);

            // --- Category 6: HDMI & CEC Topology ---
            JSONObject catCec = new JSONObject();
            put(catCec, "cecControlEnabled", aidlJson.opt("cecEnabled"));
            put(catCec, "cecAutoWakeupEnabled", aidlJson.opt("cecAutoWakeup"));
            put(catCec, "cecAutoDeviceOffEnabled", aidlJson.opt("cecAutoDeviceOff"));
            boolean hdmiAutoSwitch = (gSys != null && gSys.optBoolean("hdmiAutoSwitch", false))
                    || settingsJson.optInt("global.hdmi_auto_switch", 0) == 1;
            put(catCec, "hdmiAutoSwitch", hdmiAutoSwitch);
            if (gTv != null) {
                put(catCec, "hdmiPort1LinkConnected", gTv.optBoolean("hdmi1Connected", false));
                put(catCec, "hdmiPort2LinkConnected", gTv.optBoolean("hdmi2Connected", false));
            }
            if (gCec != null) {
                put(catCec, "earcLocalRole", gCec.optString("earcLocalRole", null));
                int cecEarc = gCec.optInt("earcDumpsysStatus", -1);
                put(catCec, "earcHandshakeState", cecEarc == earcStatus ? earcHandshakeLabel : formatEarcStatus(cecEarc));
                JSONArray ports = gCec.optJSONArray("ports");
                if (ports != null) put(catCec, "hdmiPorts", ports);
                JSONArray devices = gCec.optJSONArray("devices");
                if (devices != null) {
                    put(catCec, "cecDevices", devices);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject d = devices.optJSONObject(i);
                        if (d != null) {
                            if (sb.length() > 0) sb.append(" • ");
                            sb.append(d.optString("displayName", "Device"))
                              .append(" (Port ").append(d.optInt("portId"))
                              .append(", ").append(d.optString("physicalAddress"))
                              .append(", ").append(d.optInt("powerStatus") == 0 ? "On" : "Standby")
                              .append(")");
                        }
                    }
                    put(catCec, "connectedCecSummary", sb.toString());
                }
            }

            categories.put("hdmi_cec", catCec);

            // --- Category 7: System & Firmware ---
            JSONObject catSystem = new JSONObject();
            String devCustomName = settingsJson.optString("global.device_name", "");
            String showName = gSys != null ? gSys.optString("deviceShowName", "") : "";
            String marketingName = !devCustomName.isEmpty() && !showName.isEmpty()
                    ? devCustomName + " (" + showName + ")"
                    : (!showName.isEmpty() ? showName : Build.MODEL);
            put(catSystem, "deviceName", marketingName);
            put(catSystem, "deviceModel", Build.MODEL + " / " + sysPropsJson.optString("ro.product.odm.model", "")
                    + " (" + sysPropsJson.optString("ro.boot.xgimi.modelname", "") + ")");
            put(catSystem, "xgimiProductCode", nonEmpty(aidlJson.optString("currentProduct", ""),
                    sysPropsJson.optString("ro.vendor.xgimi.product.name", "")));
            put(catSystem, "socPlatform", sysPropsJson.optString("ro.soc.manufacturer", "MediaTek") + " "
                    + sysPropsJson.optString("ro.soc.model", "") + " ("
                    + sysPropsJson.optString("ro.board.platform", "") + " / "
                    + aidlJson.optString("currentChip", "") + ")");
            put(catSystem, "serialNumber", sysPropsJson.optString("ro.vendor.mtk.serialno", null));
            put(catSystem, "androidVersion", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            put(catSystem, "securityPatch", Build.VERSION.SECURITY_PATCH);
            put(catSystem, "buildId", Build.DISPLAY);
            put(catSystem, "gmuiVersion", nonEmpty(
                    gCfg != null ? gCfg.optString("gmuiVersion", "") : "",
                    aidlJson.optString("currentGmuiVersion", "")));
            put(catSystem, "productFirmwareVersion", nonEmpty(
                    aidlJson.optString("currentProductVersion", ""),
                    gCfg != null ? gCfg.optString("updateOtaName", "") : ""));
            put(catSystem, "xgimiApiVersion", gCfg != null && gCfg.has("xgimiApiVersion")
                    ? gCfg.opt("xgimiApiVersion") : aidlJson.opt("xgimiApiVersion"));
            if (gSys != null) {
                put(catSystem, "systemSubVersions", gSys.optString("systemVersionInfo", null));
            }
            if (gDisp != null) {
                put(catSystem, "dlpFirmwareVersion", gDisp.optString("dlpImageVersion", null));
                put(catSystem, "whiteBalanceHalVersion", gDisp.optString("whiteBalanceHalVersion", null));
            }
            put(catSystem, "bluetoothRemoteFirmware", sysPropsJson.optString("persist.vendor.xgimi.atvrc.version", null));
            put(catSystem, "uptime", sysResJson.optString("uptimeFormatted", null));
            put(catSystem, "memoryUsage", sysResJson.optString("ramFormatted", null));
            put(catSystem, "storageUsage", sysResJson.optString("storageFormatted", null));

            categories.put("system", catSystem);

            // --- Quick Summary Bar ---
            put(summary, "deviceName", catSystem.optString("deviceName", marketingName));
            put(summary, "inputSource", inputLabel);
            put(summary, "resolution", signalRes != null ? signalRes : activeDisplayMode);
            put(summary, "hdrStatus", catDisplay.optString("hdrStatus", hdrLabel));
            put(summary, "brightnessMode", catBrightness.optString("luminancePowerMode", luminanceModeLabel)
                    + (dlpLumensLvl > 0 ? " • Lvl " + dlpLumensLvl + "/10" : ""));
            put(summary, "pictureMode", catBrightness.optString("pictureMode", picModeLabel));
            put(summary, "audioOutput", catAudio.optString("activeOutputDevice", audioOutLabel));
            put(summary, "digitalAudioFormat", catAudio.optString("digitalOutputPassThroughFormat", digitalAudioFormatLabel));
            put(summary, "systemTempC", sysTempC >= 0 ? sysTempC : null);
            put(summary, "ledOn", ledOn);

            res.put("ok", true);
            res.put("ts", System.currentTimeMillis());
            res.put("collectionMs", System.currentTimeMillis() - startMs);
            if (gmpfError != null) res.put("gmpfWarning", gmpfError);
            res.put("summary", summary);
            res.put("categories", categories);

            // Include raw telemetry for full transparency / debugging
            JSONObject raw = new JSONObject();
            raw.put("aidl", aidlJson);
            raw.put("gmpf", gmpfJson);
            raw.put("mtkPq", mtkSettings != null ? mtkSettings : (gMtkPq != null ? gMtkPq : JSONObject.NULL));
            if (tvSourcesStatus != null) raw.put("tvSourcesStatus", tvSourcesStatus);
            raw.put("frameworkDisplay", fwDisplay);
            raw.put("frameworkAudio", fwAudio);
            raw.put("settings", settingsJson);
            raw.put("systemProperties", sysPropsJson);
            raw.put("systemResources", sysResJson);
            res.put("raw", raw);
        } catch (Throwable t) {
            try { res.put("error", t.getMessage()); } catch (Throwable ignore) {}
        }

        return res;
    }

    private static String nonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return null;
    }

    // ---- Framework Collectors ----

    private static JSONObject collectFrameworkDisplay(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) {
                    put(o, "name", d.getName());
                    put(o, "state", d.getState());
                    put(o, "refreshRate", Math.round(d.getRefreshRate() * 100.0) / 100.0);
                    Display.Mode mode = d.getMode();
                    if (mode != null) {
                        put(o, "activeMode", mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                                + " @ " + fmtDouble(mode.getRefreshRate()) + " Hz");
                        put(o, "width", mode.getPhysicalWidth());
                        put(o, "height", mode.getPhysicalHeight());
                    }
                    Display.Mode[] modes = d.getSupportedModes();
                    if (modes != null) {
                        JSONArray arr = new JSONArray();
                        for (Display.Mode m : modes) {
                            arr.put(m.getPhysicalWidth() + "x" + m.getPhysicalHeight()
                                    + " @ " + fmtDouble(m.getRefreshRate()) + " Hz");
                        }
                        put(o, "supportedModes", arr);
                    }
                    Display.Mode activeMode = mode != null ? mode : (modes != null && modes.length > 0 ? modes[0] : null);
                    if (activeMode != null) {
                        int[] types = activeMode.getSupportedHdrTypes();
                        if (types != null) {
                            StringBuilder sb = new StringBuilder();
                            for (int t : types) {
                                if (sb.length() > 0) sb.append(", ");
                                if (t == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) sb.append("Dolby Vision");
                                else if (t == Display.HdrCapabilities.HDR_TYPE_HDR10) sb.append("HDR10");
                                else if (t == Display.HdrCapabilities.HDR_TYPE_HLG) sb.append("HLG");
                                else if (t == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) sb.append("HDR10+");
                                else sb.append("Type ").append(t);
                            }
                            put(o, "supportedHdrTypes", sb.toString());
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 24) {
                        Display.HdrCapabilities hdr = d.getHdrCapabilities();
                        if (hdr != null) {
                            put(o, "hdrMaxLuminance", Math.round(hdr.getDesiredMaxLuminance() * 10.0) / 10.0);
                            put(o, "hdrMaxAvgLuminance", Math.round(hdr.getDesiredMaxAverageLuminance() * 10.0) / 10.0);
                            put(o, "hdrMinLuminance", Math.round(hdr.getDesiredMinLuminance() * 1000.0) / 1000.0);
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 26) {
                        put(o, "isHdrCapable", d.isHdr());
                        put(o, "isWideColorGamut", d.isWideColorGamut());
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        put(o, "minimalPostProcessingSupported", d.isMinimalPostProcessingSupported());
                    }
                }
            }
        } catch (Throwable ignore) {}
        return o;
    }

    private static JSONObject collectFrameworkAudio(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                put(o, "musicVolume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                put(o, "musicMaxVolume", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                if (Build.VERSION.SDK_INT >= 23) {
                    put(o, "musicMuted", am.isStreamMute(AudioManager.STREAM_MUSIC));
                    AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                    JSONArray arr = new JSONArray();
                    String activeRoute = null;
                    if (devs != null) {
                        for (AudioDeviceInfo dev : devs) {
                            int t = dev.getType();
                            String typeName = audioDeviceTypeToString(t);
                            CharSequence prod = dev.getProductName();
                            String entry = typeName + (prod != null && prod.length() > 0 ? " (" + prod + ")" : "");
                            arr.put(entry);
                            if (t == 29 /* TYPE_HDMI_EARC */ || t == 10 /* TYPE_HDMI_ARC */
                                    || t == 8 /* TYPE_BLUETOOTH_A2DP */ || t == 6 /* TYPE_LINE_DIGITAL */) {
                                activeRoute = typeName;
                            } else if (activeRoute == null && t == 2 /* TYPE_BUILTIN_SPEAKER */) {
                                activeRoute = typeName;
                            }
                        }
                    }
                    put(o, "outputDevices", arr);
                    put(o, "activeOutputRoute", activeRoute);
                }
            }
        } catch (Throwable ignore) {}
        return o;
    }

    private static String audioDeviceTypeToString(int type) {
        switch (type) {
            case 2: return "Built-in Speaker";
            case 3: return "Wired Headphones";
            case 4: return "Wired Headset";
            case 5: return "Analog Line Out";
            case 6: return "Digital Optical / SPDIF";
            case 7: return "Bluetooth SCO";
            case 8: return "Bluetooth A2DP";
            case 9: return "HDMI Out";
            case 10: return "HDMI ARC";
            case 11: return "USB Audio Device";
            case 12: return "USB Accessory";
            case 13: return "Dock";
            case 22: return "USB Headset";
            case 26: return "BLE Headset";
            case 27: return "BLE Speaker";
            case 29: return "HDMI eARC";
            default: return "Audio Device (" + type + ")";
        }
    }

    private static JSONObject collectSettings(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            ContentResolver cr = ctx.getContentResolver();
            String[] globalIntKeys = {
                    "gm_projector_brightness",
                    "gm_projector_color_temperature",
                    "gm_color_enhancing_filter",
                    "gm_color_space",
                    "picture_advanced_performance_mode",
                    "game_picture_mode",
                    "hdmi_auto_switch",
                    "nrdp_external_surround_sound_enabled",
                    "low_power_standby_enabled",
                    "hdmi_control_enabled",
                    "hdmi_system_audio_control_enabled"
            };
            for (String k : globalIntKeys) {
                try {
                    int v = Settings.Global.getInt(cr, k);
                    o.put("global." + k, v);
                } catch (Throwable ignore) {}
            }
            String[] globalStrKeys = {
                    "device_name",
                    "picture_list_hdr",
                    "game_picture_mode_params",
                    "nrdp_audio_platform_capabilities",
                    "user_disabled_hdr_formats",
                    "TvSourcesStatus",
                    "running_package_name",
                    "pre_package_name",
                    "HDR_Mode",
                    "pq_db_write_status"
            };
            for (String k : globalStrKeys) {
                try {
                    String v = Settings.Global.getString(cr, k);
                    if (v != null) o.put("global." + k, v);
                } catch (Throwable ignore) {}
            }

            String[] systemIntKeys = {
                    "screen_brightness",
                    "smart_turbo_light_enabled",
                    "spdif_mode",
                    "volume_music_speaker",
                    "volume_music_hdmi_arc",
                    "volume_music_hdmi_earc",
                    "volume_music_headphone",
                    "AudioService_DeviceVolumeBehavior_hdmi_arc",
                    "AudioService_DeviceVolumeBehavior_hdmi_earc"
            };
            for (String k : systemIntKeys) {
                try {
                    int v = Settings.System.getInt(cr, k);
                    o.put("system." + k, v);
                } catch (Throwable ignore) {}
            }

            String[] secureStrKeys = {
                    "tv_input_hdmi_edid_version",
                    "default_input_method"
            };
            for (String k : secureStrKeys) {
                try {
                    String v = Settings.Secure.getString(cr, k);
                    if (v != null) o.put("secure." + k, v);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return o;
    }

    private static JSONObject collectSysProps(AidlClient aidl) {
        JSONObject o = new JSONObject();
        String[] keys = {
                "ro.product.model",
                "ro.product.odm.model",
                "ro.boot.xgimi.modelname",
                "ro.vendor.xgimi.product.name",
                "ro.soc.manufacturer",
                "ro.soc.model",
                "ro.board.platform",
                "ro.vendor.mtk.serialno",
                "ro.vendor.mtk.pkg_version",
                "ro.system.build.id",
                "persist.vendor.xgimi.atvrc.version",
                "persist.vendor.xgimi.system.hgig.status",
                "persist.vendor.xgimi.system.wow",
                "vendor.xgimi.wow_type",
                "vendor.xgimi.wow_value",
                "vendor.display-size",
                "vendor.mtk.osd_size",
                "vendor.xgimi.ledOn",
                "vendor.xgimi.hdmi.playing.state",
                "vendor.xgimi.system.audio.digital_output_format",
                "sys.mtk.running.application",
                "sys.xgimi.pqmode.isperf",
                "vendor.mtk.playback.running",
                "vendor.mtk.key_repeat_delay_during_playback"
        };
        Method sysPropGet = null;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sysPropGet = sp.getMethod("get", String.class, String.class);
        } catch (Throwable ignore) {}

        for (String k : keys) {
            String val = null;
            if (sysPropGet != null) {
                try {
                    val = (String) sysPropGet.invoke(null, k, "");
                } catch (Throwable ignore) {}
            }
            if ((val == null || val.isEmpty()) && aidl != null) {
                val = aidl.getSystemPropertyViaAidl(k);
            }
            if (val != null && !val.isEmpty()) {
                put(o, k, val);
            }
        }
        return o;
    }

    private static JSONObject collectSystemResources(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            long upSec = SystemClock.elapsedRealtime() / 1000L;
            long days = upSec / 86400;
            long hrs = (upSec % 86400) / 3600;
            long mins = (upSec % 3600) / 60;
            long secs = upSec % 60;
            String upStr = (days > 0 ? days + "d " : "") + hrs + "h " + mins + "m " + secs + "s";
            put(o, "uptimeSeconds", upSec);
            put(o, "uptimeFormatted", upStr);

            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMb = mi.totalMem / (1024 * 1024);
                long availMb = mi.availMem / (1024 * 1024);
                long usedMb = totalMb - availMb;
                int pct = totalMb > 0 ? (int) ((usedMb * 100) / totalMb) : 0;
                put(o, "ramTotalMb", totalMb);
                put(o, "ramUsedMb", usedMb);
                put(o, "ramAvailMb", availMb);
                put(o, "ramFormatted", usedMb + " MB / " + totalMb + " MB (" + pct + "%)");
            }

            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long totalBytes = stat.getTotalBytes();
            long availBytes = stat.getAvailableBytes();
            long usedBytes = totalBytes - availBytes;
            double totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0);
            double usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0);
            int sPct = totalBytes > 0 ? (int) ((usedBytes * 100) / totalBytes) : 0;
            put(o, "storageTotalGb", Math.round(totalGb * 10.0) / 10.0);
            put(o, "storageUsedGb", Math.round(usedGb * 10.0) / 10.0);
            put(o, "storageFormatted", fmtDouble(usedGb) + " GB / " + fmtDouble(totalGb) + " GB (" + sPct + "%)");

            put(o, "interactiveAwake", DeviceState.isAwake(ctx));
        } catch (Throwable ignore) {}
        return o;
    }

    // ---- Human-Readable Enum Formatters ----

    private static String formatInputSource(String aidlSrc, int gmpfSrc) {
        if ("E_INPUT_SOURCE_STORAGE".equals(aidlSrc)) return "Android TV / Home (Storage)";
        if ("E_INPUT_SOURCE_HDMI1".equals(aidlSrc)) return "HDMI 1 (eARC)";
        if ("E_INPUT_SOURCE_HDMI2".equals(aidlSrc)) return "HDMI 2";
        if ("E_INPUT_SOURCE_HDMI3".equals(aidlSrc)) return "HDMI 3";
        if (aidlSrc != null && !aidlSrc.isEmpty()) return aidlSrc;
        if (gmpfSrc == 23) return "HDMI 1";
        if (gmpfSrc == 24) return "HDMI 2";
        if (gmpfSrc == 34 || gmpfSrc == 0) return "Android TV / Home";
        return "Source (" + gmpfSrc + ")";
    }

    private static String resolveHdrStatus(
            int gTvHdrType,
            boolean isPlayingHdr,
            JSONObject gTv,
            String tvSourcesStatus,
            int mtkHdrType,
            String mtkPicMode,
            boolean isInternalStorage) {

        // 1. Android TV Framework / MediaCodec state from Settings.Global "TvSourcesStatus"
        if (tvSourcesStatus != null && !tvSourcesStatus.trim().isEmpty()) {
            String upper = tvSourcesStatus.trim().toUpperCase();
            if (upper.contains("DOLBYVISION")) return "Dolby Vision";
            if (upper.contains("HDR10PLUS") || upper.contains("HDR10+")) return "HDR10+";
            if (upper.contains("HDR10")) return "HDR10";
            if (upper.contains("HLG")) return "HLG (Hybrid Log-Gamma)";
            if (upper.contains("HDRVIVID")) return "HDR Vivid";
            if (upper.contains("SDR")) {
                if (mtkPicMode == null || !mtkPicMode.startsWith("DolbyVision")) {
                    return "SDR (Standard Dynamic Range)";
                }
            }
        }

        // 2. MTK PQ IPq.getHdrType HAL report (0=NONE/SDR, 1=HDR10, 2=DOLBY, 3=TCH, 4=HLG, 5=HDR10+, 6=HDRVIVID)
        if (mtkHdrType == 2) return "Dolby Vision";
        if (mtkHdrType == 1) return "HDR10";
        if (mtkHdrType == 5) return "HDR10+";
        if (mtkHdrType == 4) return "HLG (Hybrid Log-Gamma)";
        if (mtkHdrType == 6) return "HDR Vivid";
        if (mtkHdrType == 3) return "Technicolor HDR";

        // 3. Active Picture Mode indicates Dolby Vision
        if (mtkPicMode != null && mtkPicMode.startsWith("DolbyVision")) {
            return "Dolby Vision";
        }

        // 4. Fall back to GmTvManagerService (authoritative for HDMI)
        return formatHdrType(gTvHdrType, isPlayingHdr, gTv);
    }

    private static String resolvePictureMode(int aidlPicMode, String mtkPicMode) {
        if (mtkPicMode != null && !mtkPicMode.trim().isEmpty()) {
            return formatMtkPictureMode(mtkPicMode);
        }
        return formatPictureMode(aidlPicMode);
    }

    private static String formatMtkPictureMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) return null;
        mode = mode.trim();
        if ("DolbyVisionDark".equalsIgnoreCase(mode)) return "Dolby Vision Dark";
        if ("DolbyVisionBright".equalsIgnoreCase(mode)) return "Dolby Vision Bright";
        if ("DolbyVisionGame".equalsIgnoreCase(mode)) return "Dolby Vision Game";
        if ("DolbyVisionIQ".equalsIgnoreCase(mode)) return "Dolby Vision IQ";
        if ("DolbyVisionCustom".equalsIgnoreCase(mode)) return "Dolby Vision Custom";
        if ("FilmMaker".equalsIgnoreCase(mode)) return "Filmmaker Mode";
        if ("ImaxEnhanced".equalsIgnoreCase(mode)) return "IMAX Enhanced";
        if ("Standard".equalsIgnoreCase(mode)) return "Standard";
        if ("Movie".equalsIgnoreCase(mode)) return "Movie";
        if ("Sport".equalsIgnoreCase(mode)) return "Sport";
        if ("Vivid".equalsIgnoreCase(mode)) return "Vivid";
        if ("Game".equalsIgnoreCase(mode)) return "Game";
        if ("Monitor".equalsIgnoreCase(mode)) return "Monitor";
        if ("Office".equalsIgnoreCase(mode)) return "Office";
        if ("User".equalsIgnoreCase(mode)) return "Custom / User";
        return mode;
    }

    private static String formatMtkColorTemp(String ct) {
        if (ct == null || ct.trim().isEmpty()) return null;
        ct = ct.trim();
        if ("FMMSDR".equalsIgnoreCase(ct)) return "Filmmaker SDR / Warm (FMMSDR)";
        if ("Warm".equalsIgnoreCase(ct)) return "Warm";
        if ("Standard".equalsIgnoreCase(ct)) return "Standard";
        if ("Cold".equalsIgnoreCase(ct) || "Cool".equalsIgnoreCase(ct)) return "Cold / Cool";
        if ("User".equalsIgnoreCase(ct)) return "Custom / User";
        return ct;
    }

    private static JSONObject queryMtkPqFallback() {
        return MtkPqClient.queryPqInfo();
    }

    private static String formatHdrType(int hdrType, boolean isPlayingHdr, JSONObject gTv) {
        if (gTv != null) {
            if (gTv.optBoolean("isHdrVividPlaying", false)) return "HDR Vivid";
            if (gTv.optBoolean("isImaxVideoPlaying", false)) return "IMAX Enhanced";
            if (gTv.optBoolean("isFFMPlaying", false)) return "Filmmaker Mode";
        }
        switch (hdrType) {
            case 0: return isPlayingHdr ? "HDR Active" : "SDR (Standard Dynamic Range)";
            case 1: return "HDR10";
            case 2: return "HLG (Hybrid Log-Gamma)";
            case 3: return "Dolby Vision";
            case 4: return "HDR10+";
            case 5: return "HDR Vivid";
            default: return "HDR (Type " + hdrType + ")";
        }
    }

    // --- Static Enum Lookup Tables & Generic Formatter ---

    private static String formatEnum(String[] table, int index, String fallbackPrefix) {
        if (index < 0) return null;
        if (table != null && index < table.length && table[index] != null) {
            return table[index];
        }
        return fallbackPrefix != null ? fallbackPrefix + " " + index : String.valueOf(index);
    }

    private static String formatEnumMap(Map<Integer, String> map, int key, String fallbackPrefix) {
        if (key < 0) return null;
        if (map != null && map.containsKey(key)) {
            return map.get(key);
        }
        return fallbackPrefix != null ? fallbackPrefix + " " + key : String.valueOf(key);
    }

    private static final String[] DOLBY_VISION_MODES = {
        "Inactive (0)", "Dolby Vision Bright (1)", "Dolby Vision Dark (2)", "Dolby Vision Game (3)", "Dolby Vision Custom (4)"
    };
    private static final String[] COLOR_TEMPS = {
        "System (0)", "Warm (1)", "Standard (2)", "Cold / Cool (3)", "User / Custom (4)"
    };
    private static final String[] GAME_MODES = {
        "Off (0)", "Standard Low Latency (1)", "Boost Ultra Low Latency (2)"
    };
    private static final String[] MEMC_LEVELS = {
        "Off (0)", "Low (1)", "Medium (2)", "High (3)", "Real Cinema 24p (4)"
    };
    private static final String[] OFF_LOW_MED_HIGH = {
        "Off (0)", "Low / On (1)", "Medium (2)", "High (3)"
    };
    private static final String[] HDMI_RANGES = {
        "Auto (0)", "Full (0–255) (1)", "Limited (16–235) (2)"
    };
    private static final String[] ASPECT_RATIOS = {
        "Auto / 16:9 (0)", "16:9 (1)", "Auto / Original (2)", "4:3 (3)", "Point-to-Point (4)"
    };
    private static final String[] EDID_VERSIONS = {
        "Auto (0)", "HDMI 2.0 / Standard (1)", "HDMI 2.1 / Enhanced (2)", "HDMI 1.4 / Legacy (3)"
    };
    private static final String[] THREE_D_MODES = {
        null, "2D / Off (1)", "Frame Packing 3D (2)", "Side-by-Side (3)", "Top-and-Bottom (4)"
    };
    private static final String[] AUDIO_OUTPUT_DEVICES = {
        "Internal Speakers (0)", "Headphone / Line Out (1)", "HDMI ARC / eARC (2)", "SPDIF / Optical (3)", "Bluetooth Audio (4)", "USB Audio (5)"
    };
    private static final String[] ARC_TYPES = {
        "PCM (0)", "Auto (1)", "Pass-Through (2)", "Dolby Digital (AC-3) (3)", "Dolby Digital Plus (E-AC-3) (4)"
    };
    private static final String[] SPDIF_TYPES = {
        "PCM (0)", "Auto / Dolby Digital (1)", "Pass-Through / RAW (2)"
    };
    private static final String[] EARC_STATUSES = {
        "Idle / Disconnected (0)", "ARC Pending / Handshaking (1)", "ARC Connected (2)", "eARC Connected (Full Bandwidth) (3)"
    };
    private static final String[] SOUND_MODES = {
        "Film / Movie (0)", "Music (1)", "Sports / Balanced (2)", "User / Custom (3)", "Game (4)"
    };
    private static final String[] SOUND_PROCESS_TYPES = {
        "Harman / Standard (0)", "Dolby Audio (1)", "DTS Studio Sound / Virtual:X (2)"
    };
    private static final String[] PROJECTOR_PUT_MODES = {
        "Front Table (0)", "Front Ceiling (1)", "Rear Table (2)", "Rear Ceiling (3)"
    };
    private static final String[] KEYSTONE_MODES = {
        "Off / Native (0)", "Vertical Keystone (1)", "4-Point / Auto Keystone (2)", "8-Point Keystone (3)"
    };

    private static final Map<Integer, String> PICTURE_MODES = new HashMap<>();
    static {
        PICTURE_MODES.put(0, "Standard (0)");
        PICTURE_MODES.put(1, "Movie (1)");
        PICTURE_MODES.put(2, "Vivid / Highlight (2)");
        PICTURE_MODES.put(3, "User / Custom (3)");
        PICTURE_MODES.put(4, "Game (4)");
        PICTURE_MODES.put(5, "Monitor (5)");
        PICTURE_MODES.put(6, "Office (6)");
        PICTURE_MODES.put(9, "Football / Sport (9)");
        PICTURE_MODES.put(14, "Filmmaker Mode (14)");
        PICTURE_MODES.put(18, "IMAX Enhanced (18)");
    }

    private static final Map<Integer, String> COLOR_SPACES = new HashMap<>();
    static {
        COLOR_SPACES.put(0, "Auto (0)");
        COLOR_SPACES.put(1, "BT.2020 (1)");
        COLOR_SPACES.put(2, "DCI-P3 (2)");
        COLOR_SPACES.put(3, "Rec.709 / sRGB (3)");
        COLOR_SPACES.put(4, "Original / Native (4)");
        COLOR_SPACES.put(7, "Adobe RGB (7)");
    }

    private static final Map<Integer, String> LUMINANCE_MODES = new HashMap<>();
    static {
        LUMINANCE_MODES.put(0, "Standard (0)");
        LUMINANCE_MODES.put(1, "Performance / High Power (1)");
        LUMINANCE_MODES.put(2, "Eco (2)");
        LUMINANCE_MODES.put(3, "Energy Saving (3)");
        LUMINANCE_MODES.put(4, "Eye Protection (4)");
        LUMINANCE_MODES.put(6, "Custom (6)");
        LUMINANCE_MODES.put(13, "Eco (13)");
        LUMINANCE_MODES.put(15, "Ultra Performance / Boost (15)");
        LUMINANCE_MODES.put(16, "Standard / Eye Comfort (16)");
        LUMINANCE_MODES.put(17, "Bright (17)");
        LUMINANCE_MODES.put(18, "Super Performance (18)");
    }

    private static String formatDolbyVisionMode(int mode) {
        return formatEnum(DOLBY_VISION_MODES, mode, "Mode");
    }

    private static String formatLuminanceMode(int dlpMode, int gmSetting) {
        int m = dlpMode >= 0 ? dlpMode : gmSetting;
        if (m < 0) return null;
        String mapped = LUMINANCE_MODES.get(m);
        if (mapped != null) return mapped;
        if (m >= 1 && m <= 10) return "Standard (Level " + m + "/10)";
        return "Mode " + m;
    }

    private static String formatPictureMode(int mode) {
        return formatEnumMap(PICTURE_MODES, mode, "Mode");
    }

    private static String formatColorTemp(int mode) {
        return formatEnum(COLOR_TEMPS, mode, "Mode");
    }

    private static String formatGameMode(int mode) {
        return formatEnum(GAME_MODES, mode, "Mode");
    }

    private static String formatMemcLevel(int level) {
        return formatEnum(MEMC_LEVELS, level, "Level");
    }

    private static String formatOffLowMedHigh(int level) {
        return formatEnum(OFF_LOW_MED_HIGH, level, "Level");
    }

    private static String formatColorSpace(int cs) {
        return formatEnumMap(COLOR_SPACES, cs, "Color Space");
    }

    private static String formatHdmiRange(int range) {
        return formatEnum(HDMI_RANGES, range, "Range");
    }

    private static String formatAspectRatio(int ar) {
        return formatEnum(ASPECT_RATIOS, ar, "Mode");
    }

    private static String formatEdidVersion(int edid) {
        return formatEnum(EDID_VERSIONS, edid, "Version");
    }

    private static String format3dMode(int mode3d, boolean is3dTo2d) {
        if (mode3d <= 0) return "Off (2D)";
        String base = formatEnum(THREE_D_MODES, mode3d, "Mode");
        return is3dTo2d ? base + " (3D-to-2D Active)" : base;
    }

    private static String formatAudioOutputDevice(int gmpfOut, int aidlOut, String fwRoute) {
        if (fwRoute != null && !fwRoute.isEmpty()) {
            return fwRoute;
        }
        if (gmpfOut >= 0 && gmpfOut < AUDIO_OUTPUT_DEVICES.length) {
            return AUDIO_OUTPUT_DEVICES[gmpfOut];
        }
        return aidlOut >= 0 ? "Device " + aidlOut : null;
    }

    private static String formatDigitalAudioFormat(String prop, int aidlFmt, int arcType) {
        if (prop != null && !prop.isEmpty()) {
            String p = prop.trim();
            if ("pcm".equalsIgnoreCase(p)) return "PCM (Decoded Stereo)";
            if ("auto".equalsIgnoreCase(p)) return "Auto";
            if ("passthrough".equalsIgnoreCase(p) || "raw".equalsIgnoreCase(p)) return "Pass-Through (Bitstream)";
            if ("dd".equalsIgnoreCase(p) || "ac3".equalsIgnoreCase(p)) return "Dolby Digital (AC-3)";
            if ("ddp".equalsIgnoreCase(p) || "eac3".equalsIgnoreCase(p)) return "Dolby Digital Plus (E-AC-3)";
            return p.toUpperCase();
        }
        if (arcType >= 0) return formatArcType(arcType);
        if (aidlFmt >= 0) return "Format " + aidlFmt;
        return null;
    }

    private static String formatArcType(int arcType) {
        return formatEnum(ARC_TYPES, arcType, "Mode");
    }

    private static String formatSpdifType(int gmpfSpdif, int settingSpdif) {
        int v = gmpfSpdif >= 0 ? gmpfSpdif : settingSpdif;
        return formatEnum(SPDIF_TYPES, v, "Mode");
    }

    private static String formatEarcStatus(int status) {
        return formatEnum(EARC_STATUSES, status, "State");
    }

    private static String formatSoundMode(int mode) {
        return formatEnum(SOUND_MODES, mode, "Mode");
    }

    private static String formatSoundProcessType(int type) {
        return formatEnum(SOUND_PROCESS_TYPES, type, "Processor");
    }

    private static String formatProjectorPutMode(int mode) {
        return formatEnum(PROJECTOR_PUT_MODES, mode, "Mode");
    }

    private static String formatKeystoneMode(int mode) {
        return formatEnum(KEYSTONE_MODES, mode, "Mode");
    }

    private static String fmtDouble(double d) {
        if (Math.abs(d - Math.round(d)) < 0.01) return Long.toString(Math.round(d));
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    private static void put(JSONObject o, String key, Object val) {
        JsonUtils.putSafe(o, key, val);
    }
}
