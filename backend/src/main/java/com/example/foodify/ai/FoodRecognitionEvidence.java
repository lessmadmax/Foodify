package com.example.foodify.ai;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.foodify.api.ApiError;
import java.util.*;

/** Bounded untrusted classification candidates; scores are not calibrated confidence. */
public final class FoodRecognitionEvidence {
    private static final Set<String> LABELS = Set.of("apple_pie", "baby_back_ribs", "baklava", "beef_carpaccio", "beef_tartare", "beet_salad", "beignets", "bibimbap", "bread_pudding", "breakfast_burrito", "bruschetta", "caesar_salad", "cannoli", "caprese_salad", "carrot_cake", "ceviche", "cheesecake", "cheese_plate", "chicken_curry", "chicken_quesadilla", "chicken_wings", "chocolate_cake", "chocolate_mousse", "churros", "clam_chowder", "club_sandwich", "crab_cakes", "creme_brulee", "croque_madame", "cup_cakes", "deviled_eggs", "donuts", "dumplings", "edamame", "eggs_benedict", "escargots", "falafel", "filet_mignon", "fish_and_chips", "foie_gras", "french_fries", "french_onion_soup", "french_toast", "fried_calamari", "fried_rice", "frozen_yogurt", "garlic_bread", "gnocchi", "greek_salad", "grilled_cheese_sandwich", "grilled_salmon", "guacamole", "gyoza", "hamburger", "hot_and_sour_soup", "hot_dog", "huevos_rancheros", "hummus", "ice_cream", "lasagna", "lobster_bisque", "lobster_roll_sandwich", "macaroni_and_cheese", "macarons", "miso_soup", "mussels", "nachos", "omelette", "onion_rings", "oysters", "pad_thai", "paella", "pancakes", "panna_cotta", "peking_duck", "pho", "pizza", "pork_chop", "poutine", "prime_rib", "pulled_pork_sandwich", "ramen", "ravioli", "red_velvet_cake", "risotto", "samosa", "sashimi", "scallops", "seaweed_salad", "shrimp_and_grits", "spaghetti_bolognese", "spaghetti_carbonara", "spring_rolls", "steak", "strawberry_shortcake", "sushi", "tacos", "takoyaki", "tiramisu", "tuna_tartare", "waffles");
    public static Map<String,Object> normalize(JsonNode capture, int imageCount) {
        var root=capture.path("foodRecognition");
        if(root.isMissingNode() || root.isNull()) return Map.of();
        if(!"Food-101-93M".equals(root.path("model").asText()) || !"addcbf20006b2ec0eb1bdff313ef732cd8634f11".equals(root.path("revision").asText())) invalid();
        var predictions=root.path("predictions");
        if(!predictions.isArray() || predictions.isEmpty() || predictions.size()>imageCount) invalid();
        var seen=new HashSet<Integer>(); var clean=new ArrayList<Map<String,Object>>();
        for(var prediction:predictions) {
            var index=prediction.path("imageIndex");
            if(!index.isIntegralNumber() || index.asInt()<0 || index.asInt()>=imageCount || !seen.add(index.asInt())) invalid();
            var candidates=prediction.path("candidates");
            if(!candidates.isArray() || candidates.isEmpty() || candidates.size()>3) invalid();
            var names=new HashSet<String>();var entries=new ArrayList<Map<String,Object>>();
            for(var c:candidates) {
                String label=c.path("label").asText(); var score=c.path("score");double value=score.asDouble(Double.NaN);
                if(!LABELS.contains(label) || !names.add(label) || !score.isNumber() || !Double.isFinite(value) || value<0 || value>1) invalid();
                entries.add(Map.of("label",label,"score",value));
            }
            entries.sort((a,b)->Double.compare((Double)b.get("score"),(Double)a.get("score")));
            clean.add(Map.of("imageIndex",index.asInt(),"candidates",entries));
        }
        return Map.of("model","Food-101-93M","revision",root.path("revision").asText(),"predictions",clean);
    }
    /**
     * Prompt copy of normalized evidence. An image whose top score is below {@code minTopScore} loses its
     * candidates and is marked {@code LOW_TOP_SCORE}: foods outside the 101 classes (peanuts, rice, cookies)
     * scored 0.05~0.53 on PC while correct in-class photos scored 0.65 or more, and a wrong candidate list
     * pulled the AI toward a wrong name. Stored capture_info keeps every candidate and score.
     */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> forPrompt(Map<String,Object> evidence, double minTopScore) {
        if(!(evidence.get("foodRecognition") instanceof Map<?,?> recognition)) return evidence;
        var predictions=new ArrayList<Map<String,Object>>();
        for(var prediction:(List<Map<String,Object>>)recognition.get("predictions")) {
            var candidates=(List<Map<String,Object>>)prediction.get("candidates");
            double top=candidates.isEmpty()?0:((Number)candidates.getFirst().get("score")).doubleValue();
            predictions.add(top>=minTopScore ? prediction
                : Map.of("imageIndex",prediction.get("imageIndex"),"candidates",List.of(),"candidatesExcluded","LOW_TOP_SCORE"));
        }
        var copy=new LinkedHashMap<String,Object>((Map<String,Object>)recognition);
        copy.put("predictions",predictions);
        var result=new LinkedHashMap<>(evidence);
        result.put("foodRecognition",copy);
        return result;
    }
    private static void invalid() {throw ApiError.bad("INVALID_FOOD_RECOGNITION");}
}
