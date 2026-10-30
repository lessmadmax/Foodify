package com.example.foodify.ai;

import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RegionEvidenceTest {
    ObjectNode input() throws Exception {
        return (ObjectNode)new ObjectMapper().readTree("""
          {"method":"gallery_photos","regionDrafts":[{
          "schemaVersion":1,"imageIndex":0,"imageFingerprint":"%s",
          "imageWidth":100,"imageHeight":100,"coordinateSpace":"display_image_normalized",
          "foodPolygons":[[[0.1,0.1],[0.8,0.1],[0.5,0.8]]],"source":"manual",
          "reviewed":true,"volumeValidated":true,"estimatedGrams":100}]}
          """.formatted("a".repeat(64)));
    }
    @Test void storedButExcludedFromAiPrompt() throws Exception {
        var data=input();
        var stored=CaptureEvidence.normalizeForStorage(data,1);
        var region=(Map<?,?>)((List<?>)stored.get("regionDrafts")).getFirst();
        assertEquals(false,region.get("volumeValidated"));
        assertFalse(region.containsKey("estimatedGrams"));
        assertFalse(CaptureEvidence.normalize(data,1).containsKey("regionDrafts"));
    }
    @Test void rejectsPhotoIndexAndCoordinates() throws Exception {
        var data=input();((ObjectNode)data.path("regionDrafts").get(0)).put("imageIndex",2);
        assertThrows(ApiError.class,()->CaptureEvidence.normalizeForStorage(data,1));
        var other=input();((com.fasterxml.jackson.databind.node.ArrayNode)other.path("regionDrafts").get(0).path("foodPolygons").get(0).get(0)).set(0,new ObjectMapper().getNodeFactory().numberNode(2));
        assertThrows(ApiError.class,()->CaptureEvidence.normalizeForStorage(other,1));
    }
    @Test void galleryCannotClaimArCoordinates() throws Exception {
        var data=input();((ObjectNode)data.path("regionDrafts").get(0)).put("coordinateSpace","cpu_image_normalized");
        assertThrows(ApiError.class,()->CaptureEvidence.normalizeForStorage(data,1));
    }
}
