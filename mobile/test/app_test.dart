import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/api.dart';
import 'package:foodify/features/screens.dart';

class FakeAdapter implements HttpClientAdapter {
  final calls = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancel,
  ) async {
    calls.add(options);
    if (options.path == '/auth/refresh')
      return ResponseBody.fromString(
        '{"accessToken":"new","refreshToken":"next","memberId":"member"}',
        200,
        headers: {
          'content-type': ['application/json'],
        },
      );
    if (options.headers['Authorization'] == 'Bearer old')
      return ResponseBody.fromString(
        '{"code":"UNAUTHORIZED"}',
        401,
        headers: {
          'content-type': ['application/json'],
        },
      );
    return ResponseBody.fromString(
      '{}',
      200,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  testWidgets('로그인과 회원 가입 전환', (tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: LoginScreen())),
    );
    await tester.pumpAndSettle();
    expect(find.text('로그인'), findsOneWidget);
    await tester.tap(find.text('처음이라면 회원 가입'));
    await tester.pump();
    expect(find.text('회원 가입'), findsOneWidget);
  });
  test('만료 토큰 갱신 후 요청 재전송', () async {
    FlutterSecureStorage.setMockInitialValues({
      'access': 'old',
      'refresh': 'refresh',
      'member': 'member',
    });
    final adapter = FakeAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    dio.httpClientAdapter = adapter;
    final api = Api(client: dio);
    await api.request('GET', '/me');
    expect(adapter.calls.map((r) => r.path).toList(), [
      '/me',
      '/auth/refresh',
      '/me',
    ]);
    expect(adapter.calls.last.headers['Authorization'], 'Bearer new');
  });
  test('로그아웃에 인증 헤더 전송 후 토큰 삭제', () async {
    FlutterSecureStorage.setMockInitialValues({
      'access': 'new',
      'refresh': 'refresh',
      'member': 'member',
    });
    final adapter = FakeAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    dio.httpClientAdapter = adapter;
    final api = Api(client: dio);
    await api.logout();
    expect(adapter.calls.single.headers['Authorization'], 'Bearer new');
    expect(await api.storage.read(key: 'refresh'), isNull);
  });
  test('한국어 상태와 날짜 표시', () {
    expect(stateLabel('COMPLETE'), '완료');
    expect(date(DateTime(2026, 9, 7)), '2026-09-07');
  });
}
