import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('Foodify 첫 화면에 로그인 표시', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    router.go('/');
    await tester.pumpWidget(const ProviderScope(child: FoodifyApp()));
    await tester.pumpAndSettle();

    expect(find.text('로그인'), findsOneWidget);
    expect(find.text('처음이라면 회원 가입'), findsOneWidget);
  });
}
