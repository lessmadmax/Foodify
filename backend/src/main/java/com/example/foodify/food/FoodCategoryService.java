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
    static final String DISH="음식", RAW="원재료성 식품";
    /** category is the foods.category the members come from; raw groups match whole names only. */
    private record Group(String key,String label,String term,int depth,String category,Set<String> members,Map<String,String> states) {
        boolean raw() { return RAW.equals(category); }
    }
    static final String FRESH="생것";
    /** Leading state words in a name ("말린 바나나") mapped to the official 식품세분류명. */
    private static final List<Map.Entry<String,String>> STATE_WORDS=List.of(
        Map.entry("말린","말린것"),Map.entry("건조","말린것"),Map.entry("볶은","볶은것"),Map.entry("삶은","삶은것"),
        Map.entry("구운","구운것"),Map.entry("데친","데친것"),Map.entry("찐","찐것"),Map.entry("생","생것"));
    private static boolean inState(String memberState,String state) {
        return memberState.equals(state) || memberState.startsWith(state+"("); // 구운것(팬), 말린것(자연건조)
    }
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
        db.query("SELECT f.id,f.category,r.raw_json FROM foods f JOIN food_nutrition_versions v ON v.food_id=f.id JOIN nutrition_source_rows r ON r.version_id=v.id WHERE f.category IN ('음식','원재료성 식품') ORDER BY f.id,v.source_date DESC,v.id,r.dataset_id,r.row_number", (org.springframework.jdbc.core.RowCallbackHandler) rs->{
            String id=rs.getString("id");
            if(seen.contains(id)) return;
            var raw=json.read(rs.getString("raw_json"));
            String major=clean(raw.path("식품대분류명").asText(""));
            String representative=clean(raw.path("대표식품명").asText(""));
            if(major.isEmpty() && representative.isEmpty()) return;
            seen.add(id); // Latest version carrying classification; newer sparse rows can be skipped.
            if(RAW.equals(rs.getString("category"))) {
                // Raw ingredients (아몬드_볶은것, 땅콩_말린것 ...) share an official representative name (아몬드, 땅콩).
                if(!representative.isEmpty() && !major.isEmpty()) {
                    var group=groups.computeIfAbsent("raw/"+major+"/"+representative,k->new Group(k,representative,term(representative),2,RAW,new LinkedHashSet<>(),new LinkedHashMap<>()));
                    group.members().add(id);
                    group.states().put(id,clean(raw.path("식품세분류명").asText(""))); // 생것, 말린것, 볶은것 ...
                }
                return;
            }
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
        groups.computeIfAbsent(key,k->new Group(k,label,token,depth,DISH,new LinkedHashSet<>(),Map.of())).members().add(id);
    }
    /** Official major category (식품대분류명) of a food or synthetic category id, or null. */
    public String majorOf(String foodId) {
        if(foodId==null || foodId.isBlank()) return null;
        if(foodId.startsWith("category:")) {
            for(var g:groups()) {
                var keys=new ArrayList<String>(List.of(g.key()));
                for(String state:new LinkedHashSet<>(g.states().values())) keys.add(stateKey(g,state));
                for(var e:STATE_WORDS) keys.add(stateKey(g,e.getValue()));
                if(keys.stream().noneMatch(k->foodId.equals("category:"+digest(k)))) continue;
                String[] parts=g.key().split("/");
                return parts.length>1?parts[1]:null; // major/<major>, representative/<major>/<rep> or raw/<major>/<rep>
            }
            return null;
        }
        var rows=db.queryForList("SELECT r.raw_json FROM food_nutrition_versions v JOIN nutrition_source_rows r ON r.version_id=v.id WHERE v.food_id=? ORDER BY v.source_date DESC,v.id,r.dataset_id,r.row_number",String.class,foodId);
        for(String raw:rows) {
            String major=clean(json.read(raw).path("식품대분류명").asText(""));
            if(!major.isEmpty()) return major;
        }
        return null;
    }
    public Map<String,Object> fallback(String name) {
        String normalized=FoodSearchTerms.normalize(name);
        // 1) An ingredient name equal to an official raw representative name ("아몬드", "땅콩"),
        //    optionally led by a state word ("말린 바나나", "삶은 달걀").
        var raw=rawGroup(normalized);
        if(raw!=null) {
            // Default to fresh items when the representative has them; nuts etc. without 생것 use every state.
            boolean hasFresh=raw.states().values().stream().anyMatch(v->inState(v,FRESH));
            var aggregate=hasFresh?aggregate(raw,FRESH):null;
            if(aggregate==null) aggregate=aggregate(raw,"");
            if(aggregate!=null) return aggregate;
        } else {
            for(var e:STATE_WORDS) {
                if(!normalized.startsWith(e.getKey()) || normalized.length()==e.getKey().length()) continue;
                var stated=rawGroup(normalized.substring(e.getKey().length()));
                if(stated==null) continue;
                var aggregate=aggregate(stated,e.getValue()); // null when that state is not in the data -> next stage
                if(aggregate!=null) return aggregate;
                break;
            }
        }
        // 2) Dish categories by name suffix ("무청김치" -> 김치류).
        var matches=groups().stream().filter(g->!g.raw()).filter(g->normalized.endsWith(g.term()) || normalized.equals(FoodSearchTerms.normalize(g.label())))
            .sorted(Comparator.<Group>comparingInt(g->g.term().length()).reversed().thenComparing(Comparator.comparingInt(Group::depth).reversed()).thenComparing(Group::key)).toList();
        for(int i=0;i<matches.size();i++) {
            var group=matches.get(i);
            if(i+1<matches.size() && group.term().length()==matches.get(i+1).term().length() && group.depth()==matches.get(i+1).depth()) return null;
            var aggregate=aggregate(group);
            if(aggregate!=null) return aggregate;
        }
        return null;
    }
    /** Raw group whose representative name equals the term; the major with more items wins, ties are ambiguous. */
    private Group rawGroup(String normalized) {
        var raw=groups().stream().filter(g->g.raw() && (normalized.equals(g.term()) || normalized.equals(FoodSearchTerms.normalize(g.label()))))
            .sorted(Comparator.<Group>comparingInt(g->g.members().size()).reversed().thenComparing(Group::key)).toList();
        if(raw.isEmpty() || raw.size()>1 && raw.get(0).members().size()==raw.get(1).members().size()) return null;
        return raw.getFirst();
    }
    private static String stateKey(Group group,String state) { return state.isEmpty()?group.key():group.key()+"#"+state; }
    private Map<String,Object> aggregate(Group group) { return aggregate(group,""); }
    /** state "" uses every member; otherwise only raw members in that 식품세분류명. */
    private Map<String,Object> aggregate(Group group,String state) {
        var rows=new ArrayList<Map<String,Object>>();
        var ids=new ArrayList<>(group.members());
        if(!state.isEmpty()) ids.removeIf(id->!inState(group.states().getOrDefault(id,""),state));
        if(ids.isEmpty()) return null;
        for(int i=0;i<ids.size();i+=500) {
            var batch=ids.subList(i,Math.min(ids.size(),i+500));
            var args=new ArrayList<Object>(); args.add(group.category()); args.addAll(batch);
            rows.addAll(db.queryForList("SELECT * FROM foods WHERE category=? AND searchable=TRUE AND basis_unit='g' AND basis_grams>0 AND kcal>=0 AND carbs>=0 AND protein>=0 AND fat>=0 AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray()));
        }
        if(rows.isEmpty()) return null;
        // Prefer general dish data; many branded variants should not dominate a generic category.
        var generic=rows.stream().filter(r->clean(Objects.toString(r.get("manufacturer"),"")).isEmpty()).toList();
        boolean general=!generic.isEmpty();
        if(general) rows=new ArrayList<>(generic);
        rows.sort(Comparator.comparing(r->r.get("id").toString()));
        var result=new LinkedHashMap<String,Object>();
        String label=state.isEmpty()?group.label():group.label()+"("+state+")";
        result.put("id","category:"+digest(stateKey(group,state))); result.put("name",label);
        result.put("category",group.category()); result.put("basis_unit","g"); result.put("basis_grams",new BigDecimal("100")); result.put("searchable",true);
        result.put("manufacturer",""); result.put("matchType","CATEGORY_FALLBACK");
        result.put("categoryName",label); result.put("sampleCount",rows.size());
        result.put("aggregationMethod","MEDIAN_PER_100G");
        result.put("samplePolicy",group.raw()?"RAW_REPRESENTATIVE":general?"GENERAL_DISHES":"ALL_CATEGORY_DISHES");
        String[] keyParts=group.key().split("/");
        if(keyParts.length>1) result.put("majorCategory",keyParts[1]);
        if(group.raw()) result.put("rawState",state.isEmpty()?"ALL":state);
        result.put("memberIds",rows.stream().map(r->r.get("id")).toList());
        result.put("source","공공 영양 DB · "+label+(group.raw()?" 원재료 중앙값":" 범주 중앙값"));
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
        result.put("fallbackNotice",group.raw()
            ? label+" 원재료 영양정보 "+rows.size()+"건"+(state.isEmpty()?"(여러 상태)":"")+"을 100g 기준으로 환산한 중앙값을 적용한 참고 추정값입니다. 볶음·말림 등 실제 상태에 따라 차이가 있습니다."
            : group.label()+" 범주의 영양정보 "+rows.size()+"건을 100g 기준으로 환산한 중앙값을 적용한 참고 추정값입니다. 실제 음식의 재료·조리법에 따라 차이가 있습니다.");
        return result;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
