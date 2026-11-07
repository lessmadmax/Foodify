package com.example.foodify.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Whitelisted, untrusted client measurements; never authoritative mass.
 *
 * <p>The AR summary is optional evidence. When it is unusable the photo is still analysed:
 * the AR part is dropped, the capture falls back to {@code guided_photos}, and the reasons are
 * recorded in {@code arExcluded}. Depth age is stored for analysis but is not a rejection rule,
 * because only the scene median distance reaches the model and ARCore reprojects older raw depth.
 */
public final class CaptureEvidence {
    /** Same minimum as the app frame selector (ArCaptureExport.kt). */
    static final double MIN_CONFIDENT_PIXEL_FRACTION = 0.05;
    /** Photo/AR frame pairing bound (ArCaptureExport.kt). */
    static final double MAX_CAMERA_AGE_MS = 50;
    /** Sanity bound only; values above this indicate a broken timestamp, not stale depth. */
    static final double MAX_RECORDED_DEPTH_AGE_MS = 60_000;

    private CaptureEvidence() {}
    public static Map<String,Object> normalizeForStorage(JsonNode input, int imageCount) {
        var result=normalize(input,imageCount);
        var regions=RegionEvidence.normalize(input,imageCount);
        if(!regions.isEmpty()) result.put("regionDrafts",regions);
        return result;
    }
    public static Map<String,Object> normalize(JsonNode input, int imageCount) {
        String method = input.path("method").asText("guided_photos");
        if (!List.of("guided_photos", "gallery_photos", "ar_assisted_photo").contains(method)) method = "guided_photos";
        var result = new LinkedHashMap<String,Object>();
        result.put("method", method); result.put("volumeValidated", false);
        var recognition = FoodRecognitionEvidence.normalize(input,imageCount);
        if (!recognition.isEmpty()) result.put("foodRecognition",recognition);
        if (!method.equals("ar_assisted_photo")) return result;
        var reasons = new ArrayList<String>();
        var ar = arSummary(input.path("ar"), imageCount, reasons);
        if (reasons.isEmpty()) {
            result.put("ar", ar);
        } else {
            result.put("method", "guided_photos");
            result.put("arExcluded", Map.of("requestedMethod", "ar_assisted_photo", "reasons", List.copyOf(reasons)));
        }
        return result;
    }
    private static Map<String,Object> arSummary(JsonNode ar, int imageCount, List<String> reasons) {
        var clean = new LinkedHashMap<String,Object>();
        if (imageCount != 1 || !ar.path("imageIndex").isIntegralNumber() || ar.path("imageIndex").asInt() != 0) {
            reasons.add("IMAGE_MISMATCH");
            return clean;
        }
        clean.put("imageIndex", 0);
        var fields = new ArrayList<String>();
        Double depth = number(ar,"sceneMedianDepthMm",1,65535,fields);
        Double confident = number(ar,"confidentPixelFraction",0,1,fields);
        Double depthAge = number(ar,"depthAgeMs",0,MAX_RECORDED_DEPTH_AGE_MS,fields);
        Double cameraAge = number(ar,"cameraAgeMs",0,10_000,fields);
        Double width = number(ar,"imageWidth",1,10000,fields);
        Double height = number(ar,"imageHeight",1,10000,fields);
        List<Double> focal = pair(ar,"focalLengthPixels",1,100000,fields);
        List<Double> principal = pair(ar,"principalPointPixels",0,10000,fields);
        if (!fields.isEmpty()) reasons.add("INVALID_AR_FIELDS");
        if (confident != null && confident < MIN_CONFIDENT_PIXEL_FRACTION) reasons.add("LOW_DEPTH_CONFIDENCE");
        if (cameraAge != null && cameraAge > MAX_CAMERA_AGE_MS) reasons.add("CAMERA_TIMING");
        clean.put("sceneMedianDepthMm", depth);
        clean.put("confidentPixelFraction", confident);
        clean.put("depthAgeMs", depthAge);
        clean.put("cameraAgeMs", cameraAge);
        clean.put("imageWidth", width);
        clean.put("imageHeight", height);
        clean.put("focalLengthPixels", focal);
        clean.put("principalPointPixels", principal);
        return clean;
    }
    private static Double number(JsonNode node,String key,double min,double max,List<String> invalid) {
        var n=node.path(key); double value=n.asDouble(Double.NaN);
        if(!n.isNumber() || !Double.isFinite(value) || value<min || value>max) { invalid.add(key); return null; }
        return value;
    }
    private static List<Double> pair(JsonNode node,String key,double min,double max,List<String> invalid) {
        var array=node.path(key);
        if(!array.isArray() || array.size()!=2) { invalid.add(key); return null; }
        var values=new ArrayList<Double>();
        for(var n:array) {
            double value=n.asDouble(Double.NaN);
            if(!n.isNumber() || !Double.isFinite(value) || value<min || value>max) { invalid.add(key); return null; }
            values.add(value);
        }
        return values;
    }
}
