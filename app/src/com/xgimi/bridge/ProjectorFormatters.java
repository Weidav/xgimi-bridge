package com.xgimi.bridge;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Formatters and static lookup tables for converting raw AIDL, GMPF, and TV framework
 * hardware values to human-readable strings.
 */
public class ProjectorFormatters {

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

    public static String formatEnum(String[] table, int index, String fallbackPrefix) {
        if (index < 0) return null;
        if (table != null && index < table.length && table[index] != null) {
            return table[index];
        }
        return fallbackPrefix != null ? fallbackPrefix + " " + index : String.valueOf(index);
    }

    public static String formatEnumMap(Map<Integer, String> map, int key, String fallbackPrefix) {
        if (key < 0) return null;
        if (map != null && map.containsKey(key)) {
            return map.get(key);
        }
        return fallbackPrefix != null ? fallbackPrefix + " " + key : String.valueOf(key);
    }

    public static String formatInputSource(String aidlSrc, int gmpfSrc) {
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

    public static String resolveHdrStatus(
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

    public static String resolvePictureMode(int aidlPicMode, String mtkPicMode) {
        if (mtkPicMode != null && !mtkPicMode.trim().isEmpty()) {
            return formatMtkPictureMode(mtkPicMode);
        }
        return formatPictureMode(aidlPicMode);
    }

    public static String formatMtkPictureMode(String mode) {
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

    public static String formatMtkColorTemp(String ct) {
        if (ct == null || ct.trim().isEmpty()) return null;
        ct = ct.trim();
        if ("FMMSDR".equalsIgnoreCase(ct)) return "Filmmaker SDR / Warm (FMMSDR)";
        if ("Warm".equalsIgnoreCase(ct)) return "Warm";
        if ("Standard".equalsIgnoreCase(ct)) return "Standard";
        if ("Cold".equalsIgnoreCase(ct) || "Cool".equalsIgnoreCase(ct)) return "Cold / Cool";
        if ("User".equalsIgnoreCase(ct)) return "Custom / User";
        return ct;
    }

    public static String formatHdrType(int hdrType, boolean isPlayingHdr, JSONObject gTv) {
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

    public static String formatDolbyVisionMode(int mode) {
        return formatEnum(DOLBY_VISION_MODES, mode, "Mode");
    }

    public static String formatLuminanceMode(int dlpMode, int gmSetting) {
        int m = dlpMode >= 0 ? dlpMode : gmSetting;
        if (m < 0) return null;
        String mapped = LUMINANCE_MODES.get(m);
        if (mapped != null) return mapped;
        if (m >= 1 && m <= 10) return "Standard (Level " + m + "/10)";
        return "Mode " + m;
    }

    public static String formatPictureMode(int mode) {
        return formatEnumMap(PICTURE_MODES, mode, "Mode");
    }

    public static String formatColorTemp(int mode) {
        return formatEnum(COLOR_TEMPS, mode, "Mode");
    }

    public static String formatGameMode(int mode) {
        return formatEnum(GAME_MODES, mode, "Mode");
    }

    public static String formatMemcLevel(int level) {
        return formatEnum(MEMC_LEVELS, level, "Level");
    }

    public static String formatOffLowMedHigh(int level) {
        return formatEnum(OFF_LOW_MED_HIGH, level, "Level");
    }

    public static String formatColorSpace(int cs) {
        return formatEnumMap(COLOR_SPACES, cs, "Color Space");
    }

    public static String formatHdmiRange(int range) {
        return formatEnum(HDMI_RANGES, range, "Range");
    }

    public static String formatAspectRatio(int ar) {
        return formatEnum(ASPECT_RATIOS, ar, "Mode");
    }

    public static String formatEdidVersion(int edid) {
        return formatEnum(EDID_VERSIONS, edid, "Version");
    }

    public static String format3dMode(int mode3d, boolean is3dTo2d) {
        if (mode3d <= 0) return "Off (2D)";
        String base = formatEnum(THREE_D_MODES, mode3d, "Mode");
        return is3dTo2d ? base + " (3D-to-2D Active)" : base;
    }

    public static String formatAudioOutputDevice(int gmpfOut, int aidlOut, String fwRoute) {
        if (fwRoute != null && !fwRoute.isEmpty()) {
            return fwRoute;
        }
        if (gmpfOut >= 0 && gmpfOut < AUDIO_OUTPUT_DEVICES.length) {
            return AUDIO_OUTPUT_DEVICES[gmpfOut];
        }
        return aidlOut >= 0 ? "Device " + aidlOut : null;
    }

    public static String formatDigitalAudioFormat(String prop, int aidlFmt, int arcType) {
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

    public static String formatArcType(int arcType) {
        return formatEnum(ARC_TYPES, arcType, "Mode");
    }

    public static String formatSpdifType(int gmpfSpdif, int settingSpdif) {
        int v = gmpfSpdif >= 0 ? gmpfSpdif : settingSpdif;
        return formatEnum(SPDIF_TYPES, v, "Mode");
    }

    public static String formatEarcStatus(int status) {
        return formatEnum(EARC_STATUSES, status, "State");
    }

    public static String formatSoundMode(int mode) {
        return formatEnum(SOUND_MODES, mode, "Mode");
    }

    public static String formatSoundProcessType(int type) {
        return formatEnum(SOUND_PROCESS_TYPES, type, "Processor");
    }

    public static String formatProjectorPutMode(int mode) {
        return formatEnum(PROJECTOR_PUT_MODES, mode, "Mode");
    }

    public static String formatKeystoneMode(int mode) {
        return formatEnum(KEYSTONE_MODES, mode, "Mode");
    }

    public static String fmtDouble(double d) {
        if (Math.abs(d - Math.round(d)) < 0.01) return Long.toString(Math.round(d));
        return String.format(java.util.Locale.US, "%.2f", d);
    }
}
