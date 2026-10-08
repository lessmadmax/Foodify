package com.example.foodify.food;
import java.util.*;

public final class FoodSearchTerms {
    private FoodSearchTerms() {}
    public static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}_-]+", "");
    }
    public static List<String> stages(String query) {
        String normalized=normalize(query);
        var terms=new LinkedHashSet<String>();
        if(normalized.isBlank()) return List.of();
        terms.add(normalized);
        if(normalized.contains("버거")) {
            // Keep specific product descriptions first, then expand to a food type.
            if(normalized.contains("불고기")) terms.add("불고기버거");
            else if(normalized.contains("치즈")) terms.add("치즈버거");
            else if(normalized.contains("치킨")) terms.add("치킨버거");
            terms.add("햄버거");
        }
        return List.copyOf(terms);
    }
}
