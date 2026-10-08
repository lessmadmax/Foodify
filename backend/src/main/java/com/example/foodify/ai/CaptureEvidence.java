package com.example.foodify.ai;

import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Whitelisted, untrusted client measurements; never authoritative mass. */
public final class CaptureEvidence {
    private CaptureEvidence() {}
    public static Map<String,Object> normalize(JsonNode input, int imageCount) {
        String method = input.path("method").asText("guided_photos");
        if (!List.of("guided_photos", "gallery_photos", "ar_assisted_photo").contains(method)) method = "guided_photos";
        var result = new LinkedHashMap<String,Object>();
        result.put("method", method); result.put("volumeValidated", false);
        if (!method.equals("ar_assisted_photo")) return result;
        var ar = input.path("ar");
        if (imageCount != 1 || !ar.path("imageIndex").isIntegralNumber() || ar.path("imageIndex").asInt() != 0) throw ApiError.bad("INVALID_CAPTURE");
        var clean = new LinkedHashMap<String,Object>(); clean.put("imageIndex", 0);
        clean.put("sceneMedianDepthMm", number(ar,"sceneMedianDepthMm",1,65535));
        clean.put("confidentPixelFraction",number(ar,"confidentPixelFraction",0.05,1));
        clean.put("depthAgeMs",number(ar,"depthAgeMs",0,100));
        clean.put("cameraAgeMs",number(ar,"cameraAgeMs",0,50));
        clean.put("imageWidth",number(ar,"imageWidth",1,10000));
        clean.put("imageHeight",number(ar,"imageHeight",1,10000));
        clean.put("focalLengthPixels",pair(ar,"focalLengthPixels",1,100000));
        clean.put("principalPointPixels",pair(ar,"principalPointPixels",0,10000));
        result.put("ar",clean); return result;
    }
    private static double number(JsonNode node,String key,double min,double max) {
        var n=node.path(key); double value=n.asDouble(Double.NaN);
        if(!n.isNumber() || !Double.isFinite(value) || value<min || value>max) throw ApiError.bad("INVALID_CAPTURE");
        return value;
    }
    private static List<Double> pair(JsonNode node,String key,double min,double max) {
        var array=node.path(key);
        if(!array.isArray() || array.size()!=2) throw ApiError.bad("INVALID_CAPTURE");
        var values=new ArrayList<Double>();
        for(var n:array) {
            double value=n.asDouble(Double.NaN);
            if(!n.isNumber() || !Double.isFinite(value) || value<min || value>max) throw ApiError.bad("INVALID_CAPTURE");
            values.add(value);
        }
        return values;
    }
}
