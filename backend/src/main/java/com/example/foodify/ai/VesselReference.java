package com.example.foodify.ai;

import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Depths are provisional product examples, not measurements of the photographed vessel. */
public final class VesselReference {
    private VesselReference() {}
    public static final Map<String,Double> DEPTHS=Map.of("rice_bowl",5.8,"flat_plate",0.8,"soup_bowl",3.0,"deep_plate",3.5);
    public static Map<String,Object> normalize(JsonNode output) {
        String type=output.path("vesselType").asText("unknown");
        String confidence=output.path("confidence").asText("low");
        if(!Set.of("high","medium","low").contains(confidence)) throw ApiError.conflict("AI_INVALID_OUTPUT");
        if(!DEPTHS.containsKey(type) && !"unknown".equals(type)) throw ApiError.conflict("AI_INVALID_OUTPUT");
        var rim=new ArrayList<List<Double>>();
        var points=output.path("rimPoints");
        if(!points.isArray() || points.size()>24) throw ApiError.conflict("AI_INVALID_OUTPUT");
        for(var p:points) {
            if(!p.path("x").isNumber()||!p.path("y").isNumber()) throw ApiError.conflict("AI_INVALID_OUTPUT");
            double x=p.path("x").asDouble(),y=p.path("y").asDouble();
            if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1) throw ApiError.conflict("AI_INVALID_OUTPUT");
            rim.add(List.of(x,y));
        }
        boolean usable=DEPTHS.containsKey(type)&&"high".equals(confidence)&&rim.size()>=8;
        var result=new LinkedHashMap<String,Object>();
        result.put("vesselType",type);result.put("confidence",confidence);result.put("usable",usable);
        result.put("rimPoints",rim);result.put("depthBasis","internet_category_default_v1");
        if(usable) result.put("internalDepthCm",DEPTHS.get(type));
        result.put("volumeValidated",false);
        return result;
    }
    /** Bounded, explicitly unvalidated client measurement provenance; never changes grams. */
    public static Map<String,Object> measurement(JsonNode input) {
        var result=new LinkedHashMap<String,Object>();
        String type=input.path("vesselType").asText("unknown");
        boolean applied=input.path("applied").asBoolean(false);
        result.put("vesselType",DEPTHS.containsKey(type)?type:"unknown");
        result.put("applied",applied);result.put("volumeValidated",false);
        if(applied) {
            if(!DEPTHS.containsKey(type)) throw ApiError.bad("INVALID_VESSEL_REFERENCE");
            result.put("internalDepthCm",DEPTHS.get(type));
            result.put("depthBasis","internet_category_default_v1");
            result.put("bottomModel","flat_bottom_approximation");
            for(String key:List.of("rimDistanceMm","bottomDistanceMm","rimRmseMm","rimInliers","rimOffsetMm")) {
                double v=input.path(key).asDouble(Double.NaN);
                if(!input.path(key).isNumber()||!Double.isFinite(v)||v<0||v>1200) throw ApiError.bad("INVALID_VESSEL_REFERENCE");
                result.put(key,v);
            }
            int count=input.path("rimSamples").asInt(0);
            if(count<12||count>1_000_000) throw ApiError.bad("INVALID_VESSEL_REFERENCE");
            result.put("rimSamples",count);
            result.put("rimSource","FEATURE_POINTS".equals(input.path("rimSource").asText())?"FEATURE_POINTS":"DEPTH");
            var points=new ArrayList<List<Double>>();
            for(var p:input.path("rimPoints")) {
                if(points.size()>=24||!p.isArray()||p.size()!=2||!p.get(0).isNumber()||!p.get(1).isNumber()) throw ApiError.bad("INVALID_VESSEL_REFERENCE");
                double x=p.get(0).asDouble(),y=p.get(1).asDouble();
                if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw ApiError.bad("INVALID_VESSEL_REFERENCE");
                points.add(List.of(x,y));
            }
            if(points.size()<8)throw ApiError.bad("INVALID_VESSEL_REFERENCE");
            result.put("rimPoints",points);
        } else result.put("reason",input.path("reason").asText("vessel_not_available_or_uncertain").substring(0,Math.min(100,input.path("reason").asText("vessel_not_available_or_uncertain").length())));
        return result;
    }
}
