package com.example.foodify.food;

import com.example.foodify.api.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Categories are derived from official source metadata, never per-dish substitutions. */
@Service
public class FoodCategoryService {
    private final JdbcTemplate db;
    private final Json json;
    private volatile List<Group> index;
    private record Group(String key,String label,String term,int depth,Set<String> members) {}
    public FoodCategoryService(JdbcTemplate db,Json json) { this.db=db;this.json=json; }
    public synchronized void invalidate() { index=null; }
    private static String clean(String s) {
        return s==null || Set.of("","해당없음","기타","기타류").contains(s.trim())?"":s.trim();
    }
    private static String term(String label) {
        String s=FoodSearchTerms.normalize(label);
        return s.endsWith("류")?s.substring(0,s.length()-1):s;
    }
    private synchronized List<Group> groups() {
        if(index!=null) return index;
        var groups=new LinkedHashMap<String,Group>();
        var seen=new HashSet<String>();
        db.query("SELECT f.id,r.raw_json FROM foods f JOIN food_nutrition_versions v ON v.food_id=f.id JOIN nutrition_source_rows r ON r.version_id=v.id WHERE f.category='음식' ORDER BY f.id,v.source_date DESC,v.id,r.dataset_id,r.row_number", (org.springframework.jdbc.core.RowCallbackHandler) rs->{
            String id=rs.getString("id");
            if(seen.contains(id)) return;
            var raw=json.read(rs.getString("raw_json"));
            String major=clean(raw.path("식품대분류명").asText(""));
            String representative=clean(raw.path("대표식품명").asText(""));
            if(major.isEmpty() && representative.isEmpty()) return;
            seen.add(id); // Latest version carrying classification; newer sparse rows can be skipped.
            if(!major.isEmpty()) add(groups,"major/"+major,major,0,id);
            if(!representative.isEmpty() && !term(representative).equals(term(major))) add(groups,"representative/"+major+"/"+representative,representative,1,id);
        });
        index=List.copyOf(groups.values());
        return index;
    }
    private static void add(Map<String,Group> groups,String key,String label,int depth,String id) {
        String token=term(label);
        // Avoid broad combined labels such as "밥 및 죽류" and ambiguous one-character suffixes.
        if(token.length()<2 || token.contains("및") || token.contains("/")) return;
        groups.computeIfAbsent(key,k->new Group(k,label,token,depth,new LinkedHashSet<>())).members().add(id);
    }
    public Map<String,Object> fallback(String name) {
        String normalized=FoodSearchTerms.normalize(name);
        var matches=groups().stream().filter(g->normalized.endsWith(g.term()) || normalized.equals(FoodSearchTerms.normalize(g.label())))
            .sorted(Comparator.<Group>comparingInt(g->g.term().length()).reversed().thenComparing(Comparator.comparingInt(Group::depth).reversed()).thenComparing(Group::key)).toList();
        for(int i=0;i<matches.size();i++) {
            var group=matches.get(i);
            if(i+1<matches.size() && group.term().length()==matches.get(i+1).term().length() && group.depth()==matches.get(i+1).depth()) return null;
            var aggregate=aggregate(group);
            if(aggregate!=null) return aggregate;
        }
        return null;
    }
    private Map<String,Object> aggregate(Group group) {
        var rows=new ArrayList<Map<String,Object>>();
        var ids=new ArrayList<>(group.members());
        for(int i=0;i<ids.size();i+=500) {
            var batch=ids.subList(i,Math.min(ids.size(),i+500));
            rows.addAll(db.queryForList("SELECT * FROM foods WHERE category='음식' AND searchable=TRUE AND basis_unit='g' AND basis_grams>0 AND kcal>=0 AND carbs>=0 AND protein>=0 AND fat>=0 AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",batch.toArray()));
        }
        if(rows.isEmpty()) return null;
        // Prefer general dish data; many branded variants should not dominate a generic category.
        var generic=rows.stream().filter(r->clean(Objects.toString(r.get("manufacturer"),"")).isEmpty()).toList();
        boolean general=!generic.isEmpty();
        if(general) rows=new ArrayList<>(generic);
        rows.sort(Comparator.comparing(r->r.get("id").toString()));
        var result=new LinkedHashMap<String,Object>();
        result.put("id","category:"+digest(group.key())); result.put("name",group.label());
        result.put("category","음식"); result.put("basis_unit","g"); result.put("basis_grams",new BigDecimal("100")); result.put("searchable",true);
        result.put("manufacturer",""); result.put("matchType","CATEGORY_FALLBACK");
        result.put("categoryName",group.label()); result.put("sampleCount",rows.size());
        result.put("aggregationMethod","MEDIAN_PER_100G"); result.put("samplePolicy",general?"GENERAL_DISHES":"ALL_CATEGORY_DISHES");
        result.put("memberIds",rows.stream().map(r->r.get("id")).toList());
        result.put("source","공공 영양 DB · "+group.label()+" 범주 중앙값");
        result.put("source_version",digest(json.write(rows)));
        result.put("sourceNames",rows.stream().map(r->r.get("source")).distinct().toList());
        var ranges=new LinkedHashMap<String,Object>();
        for(String nutrient:List.of("kcal","carbs","protein","fat")) {
            var values=rows.stream().map(r->new BigDecimal(r.get(nutrient).toString()).multiply(new BigDecimal("100")).divide(new BigDecimal(r.get("basis_grams").toString()),8,RoundingMode.HALF_UP)).sorted().toList();
            int count=values.size();
            BigDecimal median=count%2==1?values.get(count/2):values.get(count/2-1).add(values.get(count/2)).divide(new BigDecimal("2"));
            result.put(nutrient,median.setScale(4,RoundingMode.HALF_UP));
            ranges.put(nutrient,Map.of("min",values.getFirst(),"max",values.getLast()));
        }
        result.put("categoryRanges",ranges);
        result.put("fallbackNotice",group.label()+" 범주의 영양정보 "+rows.size()+"건을 100g 기준으로 환산한 중앙값을 적용한 참고 추정값입니다. 실제 음식의 재료·조리법에 따라 차이가 있습니다.");
        return result;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
