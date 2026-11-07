package com.example.foodify.food;

import java.math.BigDecimal;
import java.util.*;

/**
 * Density by public nutrition DB major category (식품대분류명), for weight = AR volume x density.
 * Values: FAO/INFOODS Density Database v2.0 (2012), Table 2 (https://www.fao.org/4/ap815e/ap815e.pdf).
 * Experimental (2026-11-05 demo): not validated against scale labels; volumeValidated stays false.
 */
public final class DensityGroups {
    private DensityGroups() {}
    public record Group(String id,String label,BigDecimal density,String basis,boolean matched) {}

    private static final Map<String,Group> GROUPS=new LinkedHashMap<>();
    private static final Map<String,String> MAJOR_TO_GROUP=new HashMap<>();
    public static final Group DEFAULT=new Group("DEFAULT","분류 미확인",new BigDecimal("0.73"),"그룹 값의 중앙값(기본값)",false);
    static {
        group("RICE","밥","0.73","Rice, boiled 0.73 (KEN)","밥류","곡류, 서류 제품","곡류");
        group("NOODLE","면","0.59","Pasta/noodles, boiled 0.59 (KEN)","면 및 만두류");
        group("SOUP","국물","1.04","Soups 9개 항목 중앙값 0.99~1.09","국 및 탕류","찌개 및 전골류","죽 및 스프류");
        group("SIDE","반찬","0.70","고형 혼합 요리·고기 조리 항목 중앙값 0.6~0.83",
            "볶음류","구이류","조림류","찜류","튀김류","전·적 및 부침류","나물·숙채류","생채·무침류","김치류",
            "장아찌·절임류","젓갈류","수·조·어·육류","육류","어패류 및 기타 수산물","채소류","난류","두류","버섯류","해조류");
        group("BAKERY","빵·과자","0.42","Bread 0.29~0.42, Cake 0.415, Biscuits 0.41~0.53 (FNDDS 4.1)","빵 및 과자류");
        group("NUT","견과","0.63","Nuts 0.63 (FNDDS 4.1)","두류, 견과 및 종실류","견과 및 종실류");
        group("DRINK","음료·유제품","1.03","Milk, liquid 1.03 (UK 6th)","음료 및 차류","유제품류 및 빙과류","우유류","차류");
        group("SAUCE","양념","1.12","Sauce, soy 1.12 (FNDDS 4.1)","장류, 양념류","조미료류");
    }
    private static void group(String id,String label,String density,String basis,String... majors) {
        var g=new Group(id,label,new BigDecimal(density),basis,true);
        GROUPS.put(id,g);
        for(String major:majors) MAJOR_TO_GROUP.put(major,id);
    }
    public static Group of(String major) {
        if(major==null) return DEFAULT;
        String id=MAJOR_TO_GROUP.get(major.trim());
        return id==null?DEFAULT:GROUPS.get(id);
    }
    public static Collection<Group> all() { return Collections.unmodifiableCollection(GROUPS.values()); }
}
