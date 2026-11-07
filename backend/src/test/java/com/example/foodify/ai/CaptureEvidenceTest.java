package com.example.foodify.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
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
    private static List<?> excludedReasons(Map<String,Object> output) {
        assertEquals("guided_photos",output.get("method"));
        assertFalse(output.containsKey("ar"));
        var excluded=(Map<?,?>)output.get("arExcluded");
        assertEquals("ar_assisted_photo",excluded.get("requestedMethod"));
        return (List<?>)excluded.get("reasons");
    }
    @Test void validEvidenceStaysExperimental() throws Exception {
        var output=CaptureEvidence.normalize(valid(),1);
        assertEquals(false,output.get("volumeValidated")); assertTrue(output.containsKey("ar"));
        assertEquals("ar_assisted_photo",output.get("method")); assertFalse(output.containsKey("arExcluded"));
    }
    @Test void galleryDoesNotInheritArData() throws Exception {
        var input=valid().put("method","gallery_photos");
        assertFalse(CaptureEvidence.normalize(input,1).containsKey("ar"));
    }
    @Test void photoMismatchFallsBackToPhotoAnalysis() throws Exception {
        assertEquals(List.of("IMAGE_MISMATCH"),excludedReasons(CaptureEvidence.normalize(valid(),2)));
    }
    @Test void olderDepthIsKeptAndRecorded() throws Exception {
        for (double age : new double[]{200, 1867, 5000}) {
            var input=valid(); ((ObjectNode)input.get("ar")).put("depthAgeMs",age);
            var output=CaptureEvidence.normalize(input,1);
            assertEquals("ar_assisted_photo",output.get("method"));
            assertEquals(age,((Map<?,?>)output.get("ar")).get("depthAgeMs"));
        }
    }
    @Test void brokenDepthTimestampIsExcluded() throws Exception {
        for (double age : new double[]{-1, 120_000}) {
            var input=valid(); ((ObjectNode)input.get("ar")).put("depthAgeMs",age);
            assertEquals(List.of("INVALID_AR_FIELDS"),excludedReasons(CaptureEvidence.normalize(input,1)));
        }
    }
    @Test void lowConfidenceFallsBackWithoutRejectingUpload() throws Exception {
        var input=valid(); ((ObjectNode)input.get("ar")).put("confidentPixelFraction",0.001);
        assertEquals(List.of("LOW_DEPTH_CONFIDENCE"),excludedReasons(CaptureEvidence.normalize(input,1)));
    }
    @Test void cameraPairingAndMissingCalibrationAreExcluded() throws Exception {
        var timing=valid(); ((ObjectNode)timing.get("ar")).put("cameraAgeMs",80);
        assertEquals(List.of("CAMERA_TIMING"),excludedReasons(CaptureEvidence.normalize(timing,1)));
        var other=valid(); ((ObjectNode)other.get("ar")).remove("focalLengthPixels");
        assertEquals(List.of("INVALID_AR_FIELDS"),excludedReasons(CaptureEvidence.normalize(other,1)));
    }
    @Test void storageKeepsFallbackReasonAndReanalysisStaysPhotoOnly() throws Exception {
        var input=valid(); ((ObjectNode)input.get("ar")).put("confidentPixelFraction",0.001);
        var stored=CaptureEvidence.normalizeForStorage(input,1);
        assertTrue(stored.containsKey("arExcluded"));
        var reloaded=CaptureEvidence.normalize(mapper.valueToTree(stored),1);
        assertEquals("guided_photos",reloaded.get("method")); assertFalse(reloaded.containsKey("ar"));
    }
    @Test void oldCaptureRecordsRemainCompatible() throws Exception {
        assertEquals("guided_photos",CaptureEvidence.normalize(mapper.readTree("{}"),3).get("method"));
    }
}
