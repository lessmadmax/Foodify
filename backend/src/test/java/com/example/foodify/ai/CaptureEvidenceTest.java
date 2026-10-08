package com.example.foodify.ai;

import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode valid() throws Exception {
        return (ObjectNode)mapper.readTree("""
          {"method":"ar_assisted_photo","volumeValidated":true,"ar":{
            "imageIndex":0,"sceneMedianDepthMm":650,"confidentPixelFraction":0.4,
            "depthAgeMs":0,"cameraAgeMs":0,"imageWidth":640,"imageHeight":480,
            "focalLengthPixels":[500,500],"principalPointPixels":[320,240]}}
          """);
    }
    @Test void validEvidenceStaysExperimental() throws Exception {
        var output=CaptureEvidence.normalize(valid(),1);
        assertEquals(false,output.get("volumeValidated")); assertTrue(output.containsKey("ar"));
    }
    @Test void galleryDoesNotInheritArData() throws Exception {
        var input=valid().put("method","gallery_photos");
        assertFalse(CaptureEvidence.normalize(input,1).containsKey("ar"));
    }
    @Test void preventsPhotoMismatch() throws Exception {
        var input=valid(); assertThrows(ApiError.class,()->CaptureEvidence.normalize(input,2));
    }
    @Test void rejectsStaleDepth() throws Exception {
        var input=valid(); ((ObjectNode)input.get("ar")).put("depthAgeMs",200);
        assertThrows(ApiError.class,()->CaptureEvidence.normalize(input,1));
    }
    @Test void rejectsLowQualityAndMissingCalibration() throws Exception {
        var input=valid(); ((ObjectNode)input.get("ar")).put("confidentPixelFraction",0.001);
        assertThrows(ApiError.class,()->CaptureEvidence.normalize(input,1));
        var other=valid(); ((ObjectNode)other.get("ar")).remove("focalLengthPixels");
        assertThrows(ApiError.class,()->CaptureEvidence.normalize(other,1));
    }
    @Test void oldCaptureRecordsRemainCompatible() throws Exception {
        assertEquals("guided_photos",CaptureEvidence.normalize(mapper.readTree("{}"),3).get("method"));
    }
}
