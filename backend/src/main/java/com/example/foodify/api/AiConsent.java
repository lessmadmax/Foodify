package com.example.foodify.api;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiConsent {
    public static final String VERSION = "2026-10-08-v2-chat";
    private final JdbcTemplate db;
    public AiConsent(JdbcTemplate db) { this.db = db; }
    public static void require(JdbcTemplate db, String member) {
        if (db.queryForObject("SELECT COUNT(*) FROM members WHERE id=? AND ai_consent_version=? AND ai_consented_at IS NOT NULL AND ai_revoked_at IS NULL", Integer.class, member, VERSION) != 1)
            throw ApiError.bad("AI_CONSENT_REQUIRED");
    }
    public Map<String,Object> get(String member) {
        var row = db.queryForMap("SELECT ai_consent_version,ai_consented_at,ai_revoked_at FROM members WHERE id=?", member);
        var result = new LinkedHashMap<String,Object>();
        result.put("version", VERSION);
        result.put("accepted", VERSION.equals(row.get("ai_consent_version")) && row.get("ai_consented_at") != null && row.get("ai_revoked_at") == null);
        result.put("acceptedAt", row.get("ai_consented_at"));
        result.put("revokedAt", row.get("ai_revoked_at"));
        result.put("acceptedVersion", row.get("ai_consent_version"));
        result.put("notice", """
            [시험 운영용 AI 분석 안내]
            • 목적: 음식 종류·중량 추정, 영양 DB 후보 선택, 요청한 식단 피드백 생성.
            • 전송 항목: 선택한 음식 사진(최대 3장), 첨부된 촬영·AR 깊이 요약, 음식명·재료·DB 후보. 피드백·채팅 요청 시 질문, 최근 대화 최대 10쌍, 영양 목표 대비 집계와 최근 7일 음식·영양 기록이 함께 전달됩니다. 채팅에 불필요한 개인정보나 민감한 건강정보 입력은 삼가 주세요.
            • 대화 보관: 질문·답변과 답변 당시 식단 근거는 대화 삭제 또는 계정 삭제 시까지 Foodify 서버에 보관합니다. 식단 변경·삭제는 다음 답변부터 반영되며 이전 대화 삭제는 채팅 화면에서 별도로 선택할 수 있습니다.
            • 수신 서비스: OpenAI API. 분석·피드백 요청 및 일시 오류 재시도 시 서버에서 암호화된 HTTPS로 전송합니다.
            • 국외 처리: 해외 서비스로 전송됩니다. 실제 이전 국가·수신 법인 연락처는 운영 계정의 계약 및 처리지역 확인이 필요한 상태이며, 공개 운영 전에 안내를 확정합니다.
            • 보관: Foodify의 사진·식단은 해당 기록 또는 계정 삭제 시 삭제 처리합니다. 동의 이력은 계정 삭제 시까지 보관합니다. OpenAI의 악용 모니터링 데이터는 기본적으로 최대 30일 보관되며 법령·안전상 예외가 있을 수 있습니다. 응답 저장 옵션은 꺼져 있으며 이는 모든 외부 보관의 즉시 삭제를 뜻하지 않습니다.
            • 선택과 철회: 동의는 선택사항입니다. 거절하면 AI 분석·피드백 대신 기존 기록 조회·수정을 이용할 수 있습니다. 설정 > AI 전송 안내 및 동의에서 철회할 수 있으며 이후 전송은 동의 확인으로 차단됩니다. 이미 전송이 시작된 요청은 계속 처리될 수 있고, 외부 보관 데이터에는 수신 서비스의 보관 정책이 적용됩니다. 기존 사진·기록 삭제는 별도로 선택할 수 있습니다.
            • 동의 범위가 유지되면 반복 확인 없이 이용하고, 안내 버전 변경 시 다시 동의를 받습니다. 사진 속 얼굴·문서 등 불필요한 개인정보는 촬영에서 제외해 주세요.
            공식 데이터 정책: https://developers.openai.com/api/docs/guides/your-data
            """);
        return result;
    }
    @Transactional
    public Map<String,Object> accept(String member, String version) {
        if (!VERSION.equals(version)) throw ApiError.conflict("AI_CONSENT_VERSION_CHANGED");
        db.queryForMap("SELECT id FROM members WHERE id=? FOR UPDATE", member);
        if (Boolean.TRUE.equals(get(member).get("accepted"))) return get(member);
        long now = System.currentTimeMillis();
        db.update("UPDATE members SET ai_consent_version=?,ai_consented_at=?,ai_revoked_at=NULL WHERE id=?", VERSION, now, member);
        db.update("INSERT INTO ai_consent_events VALUES(?,?,?,?,?)", UUID.randomUUID().toString(), member, VERSION, "ACCEPT", now);
        return get(member);
    }
    @Transactional
    public Map<String,Object> revoke(String member) {
        db.queryForMap("SELECT id FROM members WHERE id=? FOR UPDATE", member);
        var state = get(member);
        if (state.get("acceptedAt") != null && state.get("revokedAt") == null) {
            long now = System.currentTimeMillis();
            db.update("UPDATE members SET ai_revoked_at=? WHERE id=?", now, member);
            db.update("INSERT INTO ai_consent_events VALUES(?,?,?,?,?)", UUID.randomUUID().toString(), member, state.get("acceptedVersion"), "REVOKE", now);
        }
        return get(member);
    }
}
