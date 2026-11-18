package com.example.foodify.ai;

import com.example.foodify.api.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VesselReferenceTest {
    private final Json json=new Json(new com.fasterxml.jackson.databind.ObjectMapper());
    private String detection(String type,String confidence) {
        return "{\"vesselType\":\""+type+"\",\"confidence\":\""+confidence+"\",\"internalDepthCm\":99,\"rimPoints\":["
            +"{\"x\":0.2,\"y\":0.2},{\"x\":0.5,\"y\":0.1},{\"x\":0.8,\"y\":0.2},{\"x\":0.9,\"y\":0.5},"
            +"{\"x\":0.8,\"y\":0.8},{\"x\":0.5,\"y\":0.9},{\"x\":0.2,\"y\":0.8},{\"x\":0.1,\"y\":0.5}]}";
    }
    @Test void depthComesFromServerDefaultsNotAiNumber() {
        var r=VesselReference.normalize(json.read(detection("flat_plate","high")));
        assertEquals(0.8,r.get("internalDepthCm"));assertEquals(true,r.get("usable"));assertEquals(false,r.get("volumeValidated"));
    }
    @Test void uncertaintyNeverEnablesCorrection() {
        for(String type:new String[]{"unknown","soup_bowl"}) {
            var r=VesselReference.normalize(json.read(detection(type,"low")));
            assertEquals(false,r.get("usable"));assertFalse(r.containsKey("internalDepthCm"));
        }
    }
    @Test void nonNormalizedCoordinatesFail() {
        assertThrows(RuntimeException.class,()->VesselReference.normalize(json.read(detection("flat_plate","high").replace("0.2","1.2"))));
    }
    @Test void storedReferenceCannotClaimValidatedOrOverrideDepth() {
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.read("{\"vesselType\":\"flat_plate\",\"applied\":true,\"internalDepthCm\":99,\"volumeValidated\":true,\"rimDistanceMm\":300,\"bottomDistanceMm\":308,\"rimRmseMm\":1,\"rimInliers\":0.95,\"rimOffsetMm\":300,\"rimSamples\":20}");
        input.set("rimPoints",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
            VesselReference.normalize(json.read(detection("flat_plate","high"))).get("rimPoints")));
        var stored=VesselReference.measurement(input);
        assertEquals(.8,stored.get("internalDepthCm"));assertEquals(false,stored.get("volumeValidated"));
        input.put("rimDistanceMm",Double.NaN);
        assertThrows(RuntimeException.class,()->VesselReference.measurement(input));
    }
}
