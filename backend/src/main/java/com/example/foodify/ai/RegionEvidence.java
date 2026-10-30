package com.example.foodify.ai;

import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Stored research annotations; excluded from the AI measurement prompt. */
public final class RegionEvidence {
    private RegionEvidence() {}
    public static List<Map<String,Object>> normalize(JsonNode input, int imageCount) {
        var drafts=input.path("regionDrafts");
        if(drafts.isMissingNode()) return List.of();
        if(!drafts.isArray() || drafts.size()>imageCount) throw ApiError.bad("INVALID_CAPTURE");
        var result=new ArrayList<Map<String,Object>>();
        var indices=new HashSet<Integer>();
        int points=0;
        for(var draft:drafts) {
            int index=draft.path("imageIndex").asInt(-1);
            String space=draft.path("coordinateSpace").asText();
            String fingerprint=draft.path("imageFingerprint").asText();
            String source=draft.path("source").asText();
            int width=draft.path("imageWidth").asInt(), height=draft.path("imageHeight").asInt();
            if(!draft.path("schemaVersion").isIntegralNumber() || draft.path("schemaVersion").asInt()!=1
                    || !draft.path("imageIndex").isIntegralNumber() || index<0 || index>=imageCount || !indices.add(index)
                    || !fingerprint.matches("[a-f0-9]{64}") || !List.of("manual","android_segmentation").contains(source)
                    || !List.of("cpu_image_normalized","display_image_normalized").contains(space)
                    || !draft.path("imageWidth").isIntegralNumber() || !draft.path("imageHeight").isIntegralNumber()
                    || width<=0 || height<=0 || (long)width*height>20_000_000
                    || !draft.path("reviewed").isBoolean() || !draft.path("reviewed").asBoolean()) throw ApiError.bad("INVALID_CAPTURE");
            if(space.equals("cpu_image_normalized") &&
                    (!input.path("method").asText().equals("ar_assisted_photo") || index!=0 ||
                     input.path("ar").path("imageWidth").asInt()!=width || input.path("ar").path("imageHeight").asInt()!=height ||
                     !draft.path("frameFingerprint").asText().matches("[a-f0-9]{64}"))) throw ApiError.bad("INVALID_CAPTURE");
            var polygons=draft.path("foodPolygons");
            if(!polygons.isArray() || polygons.isEmpty() || polygons.size()>30) throw ApiError.bad("INVALID_CAPTURE");
            var cleanPolygons=new ArrayList<List<List<Double>>>();
            for(var polygon:polygons) {
                if(!polygon.isArray() || polygon.size()<3 || polygon.size()>200) throw ApiError.bad("INVALID_CAPTURE");
                var cleanPoints=new ArrayList<List<Double>>();
                for(var point:polygon) {
                    if(++points>3000 || !point.isArray() || point.size()!=2) throw ApiError.bad("INVALID_CAPTURE");
                    var values=new ArrayList<Double>();
                    for(var value:point) {
                        double n=value.asDouble(Double.NaN);
                        if(!value.isNumber() || !Double.isFinite(n) || n<0 || n>1) throw ApiError.bad("INVALID_CAPTURE");
                        values.add(n);
                    }
                    cleanPoints.add(values);
                }
                cleanPolygons.add(cleanPoints);
            }
            var clean=new LinkedHashMap<String,Object>();
            clean.put("schemaVersion",1); clean.put("imageIndex",index); clean.put("imageFingerprint",fingerprint);
            clean.put("imageWidth",width); clean.put("imageHeight",height); clean.put("coordinateSpace",space);
            clean.put("foodPolygons",cleanPolygons); clean.put("source",source); clean.put("reviewed",true);
            clean.put("volumeValidated",false);
            if(space.equals("cpu_image_normalized")) clean.put("frameFingerprint",draft.path("frameFingerprint").asText());
            result.add(clean);
        }
        return result;
    }
}
