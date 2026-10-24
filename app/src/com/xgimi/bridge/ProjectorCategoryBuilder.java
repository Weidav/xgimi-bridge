package com.xgimi.bridge;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds categorized telemetry dictionaries and summary status for projector info.
 */
public class ProjectorCategoryBuilder {

    public static class CategorizedResult {
        public final JSONObject categories;
        public final JSONObject summary;

        public CategorizedResult(JSONObject categories, JSONObject summary) {
            this.categories = categories;
            this.summary = summary;
        }
    }

    public static CategorizedResult build(
            Context ctx,
            JSONObject aidlJson,
            JSONObject gmpfJson,
            JSONObject fwDisplay,
            JSONObject fwAudio,
            JSONObject settingsJson,
            JSONObject sysPropsJson,
            JSONObject sysResJson,
            JSONObject gMtkPq) {

        JSONObject summary = new JSONObject();
        JSONObject categories = new JSONObject();

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

        JSONObject mtkSettings = gMtkPq != null ? gMtkPq.optJSONObject("settings") : null;
        int mtkHdrType = gMtkPq != null ? gMtkPq.optInt("hdrType", -1) : -1;
        String mtkPicMode = mtkSettings != null ? mtkSettings.optString("Picture_Mode", null) : null;
        String tvSourcesStatus = settingsJson.optString("global.TvSourcesStatus", null);
        boolean isPlaybackRunning = "1".equals(sysPropsJson.optString("vendor.mtk.playback.running", "0"));
        String runningApp = DeviceState.getRunningApp(ctx);
        int picMode = aidlJson.optInt("pictureMode", -1);

        // --- Category 1: Signal & Display ---
        JSONObject catDisplay = new JSONObject();
        String inputSrcRaw = aidlJson.optString("currentInputSource", "");
        int gmpfInputSrc = gTv != null ? gTv.optInt("currentInputSource", -1) : -1;
        boolean isInternalStorage = "E_INPUT_SOURCE_STORAGE".equals(inputSrcRaw) || gmpfInputSrc == 34 || gmpfInputSrc == 0 || (tvSourcesStatus != null && tvSourcesStatus.startsWith("mm|"));
        String inputLabel = ProjectorFormatters.formatInputSource(inputSrcRaw, gmpfInputSrc);
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
            signalRes = w + "x" + h + (interlace ? "i" : "p") + (fps > 0 ? " @ " + ProjectorFormatters.fmtDouble(fps) + " Hz" : "");
            put(catDisplay, "hdmiSignalTiming", signalRes);
            put(catDisplay, "hdmiMode", hdmiInfo.optBoolean("isHdmiMode", true) ? "HDMI" : "DVI");
        } else if (videoInfo != null && videoInfo.optInt("width", 0) > 0) {
            int w = videoInfo.optInt("width");
            int h = videoInfo.optInt("height");
            int fr = videoInfo.optInt("frameRate");
            double fps = fr > 1000 ? (fr / 1000.0) : (fr > 200 ? fr / 10.0 : fr);
            signalRes = w + "x" + h + (fps > 0 ? " @ " + ProjectorFormatters.fmtDouble(fps) + " Hz" : "");
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
        String hdrLabel = ProjectorFormatters.resolveHdrStatus(hdrType, isPlayingHdr, gTv, tvSourcesStatus, mtkHdrType, mtkPicMode, isInternalStorage);
        put(catDisplay, "hdrStatus", hdrLabel);
        put(catDisplay, "hdrSupportEnabled", hdrSupportEnabled);
        put(catDisplay, "hdrSettingMode", settingsJson.optString("global.picture_list_hdr", null));

        String dvPicModeLabel;
        if (mtkPicMode != null && mtkPicMode.toLowerCase().contains("dolbyvision")) {
            dvPicModeLabel = ProjectorFormatters.formatMtkPictureMode(mtkPicMode);
        } else if (gTv != null && gTv.has("dolbyVisionPicMode") && gTv.optInt("dolbyVisionPicMode", -1) > 0) {
            dvPicModeLabel = ProjectorFormatters.formatDolbyVisionMode(gTv.optInt("dolbyVisionPicMode"));
        } else if (hdrLabel.contains("Dolby Vision")) {
            dvPicModeLabel = "Active";
        } else {
            dvPicModeLabel = "Inactive (0)";
        }
        put(catDisplay, "dolbyVisionPicMode", dvPicModeLabel);

        boolean isFfmPlaying = ProjectorFormatters.isFilmMakerMode(picMode, mtkPicMode, mtkSettings, gTv);
        boolean isImaxPlaying = (gTv != null && gTv.optBoolean("isImaxVideoPlaying", false))
                || (picMode < 0 && "ImaxEnhanced".equalsIgnoreCase(mtkPicMode));
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
        put(catDisplay, "gameLowLatencyMode", ProjectorFormatters.formatGameMode(gameMode));
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
        put(catDisplay, "memcMotionCompensation", ProjectorFormatters.formatMemcLevel(memc));

        // Color Space, Range, Aspect Ratio, EDID, 3D
        int colorSpaceSetting = settingsJson.optInt("global.gm_color_space", -1);
        int dispColorSpace = gDisp != null ? gDisp.optInt("dispColorSpace", -1) : -1;
        put(catDisplay, "colorSpace", ProjectorFormatters.formatColorSpace(colorSpaceSetting >= 0 ? colorSpaceSetting : dispColorSpace));
        if (gTv != null && gTv.has("hdmiRange")) {
            put(catDisplay, "hdmiColorRange", ProjectorFormatters.formatHdmiRange(gTv.optInt("hdmiRange", 0)));
        }
        int aspect = aidlJson.optInt("aspectRatio", gTv != null ? gTv.optInt("aspectRatio", -1) : -1);
        put(catDisplay, "aspectRatio", ProjectorFormatters.formatAspectRatio(aspect));
        int edidVer = aidlJson.optInt("edidVersion", gTv != null ? gTv.optInt("edidVersion", -1) : -1);
        put(catDisplay, "edidVersion", ProjectorFormatters.formatEdidVersion(edidVer));
        int cur3d = aidlJson.optInt("current3dType", -1);
        int disp3d = gDisp != null ? gDisp.optInt("current3dFormat", 0) : 0;
        put(catDisplay, "mode3d", ProjectorFormatters.format3dMode(cur3d >= 0 ? cur3d : disp3d, aidlJson.optBoolean("is3dTo2d", false)));
        put(catDisplay, "supportedDisplayModes", fwDisplay.optJSONArray("supportedModes"));

        put(categories, "display", catDisplay);

        // --- Category 2: Picture & Brightness ---
        JSONObject catBrightness = new JSONObject();
        int gmBrightness = settingsJson.optInt("global.gm_projector_brightness", -1);
        int dlpLumensLvl = gDisp != null ? gDisp.optInt("dlpLumensLevel", -1) : -1;
        int dlpLumensMode = gDisp != null ? gDisp.optInt("dlpLumensMode", -1) : -1;

        String luminanceModeLabel = ProjectorFormatters.formatLuminanceMode(dlpLumensMode, gmBrightness);
        put(catBrightness, "luminancePowerMode", luminanceModeLabel);
        if (dlpLumensLvl > 0) {
            put(catBrightness, "brightnessLevel", dlpLumensLvl + " / 10");
        } else if (gmBrightness >= 1 && gmBrightness <= 10) {
            put(catBrightness, "brightnessLevel", gmBrightness + " / 10");
        }
        put(catBrightness, "androidBacklightValue", settingsJson.optInt("system.screen_brightness", -1));

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
        String picModeLabel = ProjectorFormatters.resolvePictureMode(picMode, mtkPicMode, mtkSettings, gTv);
        put(catBrightness, "pictureMode", picModeLabel);

        if (mtkSettings != null) {
            put(catBrightness, "pqBrightness", mtkSettings.has("Brightness") ? mtkSettings.opt("Brightness") : aidlJson.opt("brightness"));
            put(catBrightness, "pqContrast", mtkSettings.has("Contrast") ? mtkSettings.opt("Contrast") : aidlJson.opt("contrast"));
            put(catBrightness, "pqSaturation", mtkSettings.has("Saturation") ? mtkSettings.opt("Saturation") : aidlJson.opt("saturation"));
            put(catBrightness, "pqSharpness", mtkSettings.has("SR_Strength") ? mtkSettings.opt("SR_Strength") : aidlJson.opt("sharpness"));
            put(catBrightness, "pqHue", mtkSettings.has("Hue") ? mtkSettings.opt("Hue") : aidlJson.opt("hue"));

            int colorTemp = aidlJson.optInt("colorTemp", -1);
            String mtkColorTemp = mtkSettings.optString("Color_Temperature", null);
            put(catBrightness, "colorTemperatureMode", mtkColorTemp != null ? ProjectorFormatters.formatMtkColorTemp(mtkColorTemp) : ProjectorFormatters.formatColorTemp(colorTemp));

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
            put(catBrightness, "colorTemperatureMode", ProjectorFormatters.formatColorTemp(colorTemp));
        }

        if (aidlJson.has("wbRed")) {
            put(catBrightness, "whiteBalanceGains",
                    "R: " + aidlJson.optInt("wbRed") + " / G: " + aidlJson.optInt("wbGreen") + " / B: " + aidlJson.optInt("wbBlue"));
        }

        // Contrast, Black Level, Enhancements
        if (gDisp != null) {
            put(catBrightness, "dynamicBlackLevel", ProjectorFormatters.formatOffLowMedHigh(gDisp.optInt("dynamicBlackLevel", -1)));
            put(catBrightness, "contrastEnhanceLevel", ProjectorFormatters.formatOffLowMedHigh(gDisp.optInt("contrastEnhanceLvl", -1)));
            if (gDisp.optInt("colorEnhance", -1) >= 0) {
                put(catBrightness, "colorEnhance", gDisp.optInt("colorEnhance"));
            }
            put(catBrightness, "rainbowEffectReduction",
                    gDisp.optInt("rainbowReductionStatus", 0) == 0 ? "Off (0)"
                            : "Enabled (Level " + gDisp.optInt("rainbowReductionLevel", 0) + ")");
        }
        put(catBrightness, "colorEnhancingFilter", settingsJson.optInt("global.gm_color_enhancing_filter", 0) == 1);
        put(catBrightness, "noiseReduction", ProjectorFormatters.formatOffLowMedHigh(aidlJson.optInt("noiseReduction", -1)));
        if (gTv != null) {
            put(catBrightness, "dynamicContrastEnable", gTv.optBoolean("dynamicContrastEnable", false));
            put(catBrightness, "gammaLevel", gTv.optInt("gammaLevel", 0));
            put(catBrightness, "aiPqEnabled", gTv.optBoolean("aiPqEnable", false));
            put(catBrightness, "aisrSuperResolution",
                    gTv.optInt("aisrLevel", 0) == 0 ? "Off (0)" : "Level " + gTv.optInt("aisrLevel", 0));
        }

        put(categories, "brightness_picture", catBrightness);

        // --- Category 3: Audio & Pass-Through ---
        JSONObject catAudio = new JSONObject();
        int gmpfAudioOut = gAud != null ? gAud.optInt("audioOutput", -1) : -1;
        int aidlAudioOut = aidlJson.optInt("audioOutputDevices", -1);
        String activeAudioRoute = fwAudio.optString("activeOutputRoute", null);
        String audioOutLabel = ProjectorFormatters.formatAudioOutputDevice(gmpfAudioOut, aidlAudioOut, activeAudioRoute);
        put(catAudio, "activeOutputDevice", audioOutLabel);
        put(catAudio, "androidOutputDevices", fwAudio.optJSONArray("outputDevices"));

        String digOutFmtProp = sysPropsJson.optString("vendor.xgimi.system.audio.digital_output_format", null);
        int aidlDataFmt = aidlJson.optInt("audioDataFormat", -1);
        int gmpfArcType = gAud != null ? gAud.optInt("arcType", -1) : -1;
        int gmpfSpdifType = gAud != null ? gAud.optInt("spdifType", -1) : -1;
        int spdifSetting = settingsJson.optInt("system.spdif_mode", -1);

        String digitalAudioFormatLabel = ProjectorFormatters.formatDigitalAudioFormat(digOutFmtProp, aidlDataFmt, gmpfArcType);
        put(catAudio, "digitalOutputPassThroughFormat", digitalAudioFormatLabel);
        put(catAudio, "rawDigitalOutputProperty", digOutFmtProp);
        put(catAudio, "arcOutputFormat", ProjectorFormatters.formatArcType(gmpfArcType));
        put(catAudio, "spdifOutputFormat", ProjectorFormatters.formatSpdifType(gmpfSpdifType, spdifSetting));

        int earcDumpsys = gCec != null ? gCec.optInt("earcDumpsysStatus", -1) : -1;
        int earcTv = gTv != null ? gTv.optInt("earcStatus", -1) : -1;
        int earcStatus = earcDumpsys > 0 ? earcDumpsys : earcTv;
        boolean earcEnabled = (gTv != null && gTv.optBoolean("earcEnableState", false)) || earcStatus == 3;
        String earcHandshakeLabel = ProjectorFormatters.formatEarcStatus(earcStatus);
        put(catAudio, "earcEnabled", earcEnabled);
        put(catAudio, "earcHandshakeStatus", earcHandshakeLabel);
        put(catAudio, "externalSurroundSoundEnabled", settingsJson.optInt("global.nrdp_external_surround_sound_enabled", 0) == 1);

        int soundEffect = gAud != null ? gAud.optInt("soundEffect", -1) : aidlJson.optInt("soundMode", -1);
        int soundProcessType = gAud != null ? gAud.optInt("soundProcessType", -1) : -1;
        put(catAudio, "soundMode", ProjectorFormatters.formatSoundMode(soundEffect));
        put(catAudio, "soundProcessorType", ProjectorFormatters.formatSoundProcessType(soundProcessType));

        if (gAud != null) {
            put(catAudio, "dolbySurround", gAud.optBoolean("dolbySurroundEnable", false));
            put(catAudio, "dolbyVirtualSurround", gAud.optBoolean("dolbySurroundVirtual", false));
            put(catAudio, "dolbyDialogEnhancer", ProjectorFormatters.formatOffLowMedHigh(gAud.optInt("dolbyDialogEnhancer", 0)));
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

        put(categories, "audio", catAudio);

        // --- Category 4: Optics, Geometry & Sensors ---
        JSONObject catOptics = new JSONObject();
        int putMode = gDisp != null ? gDisp.optInt("projectorPutMode", -1) : -1;
        put(catOptics, "projectionPlacementMode", ProjectorFormatters.formatProjectorPutMode(putMode));
        if (gDisp != null) {
            put(catOptics, "keystoneMode", ProjectorFormatters.formatKeystoneMode(gDisp.optInt("currentKeystoneMode", -1)));
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
                put(catOptics, "tofDistanceToWall", tofMm + " mm (" + ProjectorFormatters.fmtDouble(tofMm / 1000.0) + " m)");
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

        put(categories, "optics_sensors", catOptics);

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
                put(catThermal, "powerSupplyVoltage", ProjectorFormatters.fmtDouble(v) + " V");
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

        put(categories, "thermal_power", catThermal);

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
            put(catCec, "earcHandshakeState", cecEarc == earcStatus ? earcHandshakeLabel : ProjectorFormatters.formatEarcStatus(cecEarc));
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

        put(categories, "hdmi_cec", catCec);

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

        put(categories, "system", catSystem);

        // --- Quick Summary Bar ---
        put(summary, "deviceName", catSystem.optString("deviceName", marketingName));
        put(summary, "inputSource", inputLabel);
        put(summary, "resolution", signalRes != null ? signalRes : activeDisplayMode);
        put(summary, "hdrStatus", catDisplay.optString("hdrStatus", hdrLabel));
        String lumMode = catBrightness.optString("luminancePowerMode", luminanceModeLabel);
        if (lumMode != null) {
            put(summary, "brightnessMode", lumMode + (dlpLumensLvl > 0 ? " • Lvl " + dlpLumensLvl + "/10" : ""));
        } else if (dlpLumensLvl > 0) {
            put(summary, "brightnessMode", "Level " + dlpLumensLvl + "/10");
        } else {
            put(summary, "brightnessMode", null);
        }
        put(summary, "pictureMode", catBrightness.optString("pictureMode", picModeLabel));
        put(summary, "audioOutput", catAudio.optString("activeOutputDevice", audioOutLabel));
        put(summary, "digitalAudioFormat", catAudio.optString("digitalOutputPassThroughFormat", digitalAudioFormatLabel));
        put(summary, "systemTempC", sysTempC >= 0 ? sysTempC : null);
        put(summary, "ledOn", ledOn);

        return new CategorizedResult(categories, summary);
    }

    private static String nonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return null;
    }

    private static void put(JSONObject o, String key, Object val) {
        JsonUtils.putSafe(o, key, val);
    }
}
