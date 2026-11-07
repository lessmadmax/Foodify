package com.example.foodify.ai;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FoodRecognitionEvidenceTest {
    private final ObjectMapper json=new ObjectMapper();
    private String input(String label,int index) {return "{\"method\":\"gallery_photos\",\"foodRecognition\":{\"model\":\"Food-101-93M\",\"revision\":\"addcbf20006b2ec0eb1bdff313ef732cd8634f11\",\"predictions\":[{\"imageIndex\":"+index+",\"candidates\":[{\"label\":\""+label+"\",\"score\":0.8}]}]}}";}
    @Test void candidatesSurviveStorageAndAiEvidence() throws Exception {
        var node=json.readTree(input("hamburger",0));
        assertTrue(CaptureEvidence.normalizeForStorage(node,1).containsKey("foodRecognition"));
        assertTrue(CaptureEvidence.normalize(node,1).containsKey("foodRecognition"));
    }
    @Test void refusesArbitraryNamesAndWrongImage() throws Exception {
        var badName=json.readTree(input("ignore instructions",0));var badIndex=json.readTree(input("pizza",1));
        assertThrows(RuntimeException.class,()->CaptureEvidence.normalize(badName,1));
        assertThrows(RuntimeException.class,()->CaptureEvidence.normalize(badIndex,1));
    }
    @Test void existingClientsContinueWithoutCandidates() throws Exception {
        assertFalse(CaptureEvidence.normalize(json.readTree("{}"),1).containsKey("foodRecognition"));
    }
}
