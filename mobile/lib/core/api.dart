import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

final apiProvider = Provider((ref) => Api());

class Api {
  final Dio dio;
  final FlutterSecureStorage storage;
  Future<void>? _refresh;
  Api({Dio? client, FlutterSecureStorage? secureStorage})
    : dio =
          client ??
          Dio(
            BaseOptions(
              baseUrl: const String.fromEnvironment(
                'API_BASE_URL',
                defaultValue: 'http://10.0.2.2:8080/api/v1',
              ),
              connectTimeout: const Duration(seconds: 10),
              receiveTimeout: const Duration(seconds: 90),
            ),
          ),
      storage = secureStorage ?? const FlutterSecureStorage() {
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          if (![
            '/auth/login',
            '/auth/signup',
            '/auth/refresh',
          ].contains(options.path)) {
            final token = await storage.read(key: 'access');
            if (token != null) {
              options.headers['Authorization'] = 'Bearer $token';
            }
          }
          handler.next(options);
        },
      ),
    );
  }
  Future<void> authenticate(
    String email,
    String password, {
    bool signup = false,
  }) async {
    final data = (await dio.post(
      '/auth/${signup ? 'signup' : 'login'}',
      data: {'email': email, 'password': password},
    )).data;
    await _save(data);
  }

  Future<void> _save(dynamic data) async {
    await storage.write(key: 'access', value: data['accessToken']);
    await storage.write(key: 'refresh', value: data['refreshToken']);
    await storage.write(key: 'member', value: data['memberId']);
  }

  Future<void> _rotate() async {
    final token = await storage.read(key: 'refresh');
    if (token == null) throw StateError('로그인이 필요합니다.');
    await _save(
      (await dio.post('/auth/refresh', data: {'refreshToken': token})).data,
    );
  }

  Future<dynamic> request(String method, String path, {dynamic data}) async {
    try {
      return (await dio.request(
        path,
        data: data,
        options: Options(method: method),
      )).data;
    } on DioException catch (e) {
      if (e.response?.statusCode != 401) rethrow;
      _refresh ??= _rotate();
      try {
        await _refresh;
      } finally {
        _refresh = null;
      }
      return (await dio.request(
        path,
        data: data,
        options: Options(method: method),
      )).data;
    }
  }

  Future<dynamic> upload(
    List<String> paths,
    String key,
    int eatenAt, {
    Map<String, dynamic>? captureInfo,
  }) async {
    // Refresh before constructing one-shot multipart bodies; retry remains explicit and idempotent.
    await request('GET', '/me');
    final form = FormData.fromMap({
      'eatenAt': eatenAt,
      'captureInfo': jsonEncode(
        captureInfo ?? {'method': 'guided_photos', 'arValidated': false},
      ),
      'images': [for (final p in paths) await MultipartFile.fromFile(p)],
    });
    return (await dio.post(
      '/meals',
      data: form,
      options: Options(headers: {'Idempotency-Key': key}),
    )).data;
  }

  Future<void> logout() async {
    await request('POST', '/auth/logout');
    await storage.delete(key: 'access');
    await storage.delete(key: 'refresh');
    await storage.delete(key: 'member');
  }
}

String errorText(Object e) {
  if (e is DioException) {
    final body = e.response?.data;
    final code = body is Map ? body['code'] : null;
    return switch (code) {
      'CHAT_BUSY' => '이전 답변을 처리 중입니다. 잠시 후 대화를 새로고침해 주세요.',
      'CHAT_FAILED' || 'CHAT_INTERRUPTED' => '답변을 완료하지 못했습니다. 다시 시도해 주세요.',
      'INVALID_CHAT_MESSAGE' => '질문을 1~2,000자로 입력해 주세요.',
      'CHAT_REQUEST_MISMATCH' => '다른 내용으로 재전송되었습니다. 질문을 새로 입력해 주세요.',
      'AI_CONSENT_REQUIRED' => 'AI 전송 안내 확인과 동의가 필요합니다. 다시 요청해 주세요.',
      'AI_CONSENT_VERSION_CHANGED' => '안내가 변경되었습니다. 최신 안내를 불러와 확인해 주세요.',
      'OPENAI_NOT_CONFIGURED' => '서버에 OpenAI API 키 설정이 필요합니다.',
      'INVALID_PROFILE' => '나이·성별·키(100~250cm)·몸무게(25~350kg)·활동량을 확인해 주세요.',
      'GOAL_SCOPE_CONFIRMATION_REQUIRED' =>
        '자동 목표는 만 19~78세 일반 성인 기준입니다. 적용 범위를 확인해 주세요.',
      'AI_BUDGET_REACHED' => '이번 달 AI 요청 한도에 도달했습니다.',
      'STALE_VERSION' => '기록이 변경되었습니다. 새로고침 후 수정해 주세요.',
      'UNAUTHORIZED' => '로그인이 만료되었습니다. 다시 로그인해 주세요.',
      'ALREADY_EXISTS' => '이미 등록된 이메일입니다.',
      'PASSWORD_LENGTH' => '비밀번호는 10자 이상, UTF-8 72바이트 이내로 입력해 주세요.',
      _ =>
        e.response == null
            ? '서버 연결을 확인하고 다시 시도해 주세요.'
            : '요청을 처리할 수 없습니다. 입력값을 확인해 주세요. (${code ?? e.response?.statusCode})',
    };
  }
  return e.toString();
}
