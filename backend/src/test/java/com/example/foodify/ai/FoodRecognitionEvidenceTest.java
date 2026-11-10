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
    @Test void lowTopScoreDropsCandidatesOnlyFromPrompt() throws Exception {
        var node=json.readTree("{\"method\":\"gallery_photos\",\"foodRecognition\":{\"model\":\"Food-101-93M\",\"revision\":\"addcbf20006b2ec0eb1bdff313ef732cd8634f11\",\"predictions\":["
            +"{\"imageIndex\":0,\"candidates\":[{\"label\":\"foie_gras\",\"score\":0.11},{\"label\":\"falafel\",\"score\":0.05}]},"
            +"{\"imageIndex\":1,\"candidates\":[{\"label\":\"hamburger\",\"score\":0.6}]}]}}");
        var stored=CaptureEvidence.normalizeForStorage(node,2);
        var prompt=json.valueToTree(FoodRecognitionEvidence.forPrompt(CaptureEvidence.normalize(node,2),0.6));
        var low=prompt.path("foodRecognition").path("predictions").get(0);var kept=prompt.path("foodRecognition").path("predictions").get(1);
        assertEquals(0,low.path("imageIndex").asInt());
        assertEquals(0,low.path("candidates").size());
        assertEquals("LOW_TOP_SCORE",low.path("candidatesExcluded").asText());
        assertEquals("hamburger",kept.path("candidates").get(0).path("label").asText());
        assertFalse(prompt.toString().contains("foie_gras"));
        assertTrue(json.writeValueAsString(stored).contains("foie_gras"));
    }
    @Test void promptWithoutRecognitionIsUnchanged() {
        var evidence=java.util.Map.<String,Object>of("method","guided_photos");
        assertSame(evidence,FoodRecognitionEvidence.forPrompt(evidence,0.6));
    }
    @Test void existingClientsContinueWithoutCandidates() throws Exception {
        assertFalse(CaptureEvidence.normalize(json.readTree("{}"),1).containsKey("foodRecognition"));
    }
}
