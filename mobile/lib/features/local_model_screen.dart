import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

class LocalModelScreen extends StatefulWidget {
  const LocalModelScreen({super.key});
  @override
  State<LocalModelScreen> createState() => _LocalModelScreenState();
}

class _LocalModelScreenState extends State<LocalModelScreen> {
  static const channel = MethodChannel('foodify/local_model');
  Map status = {};
  Map? result;
  String? photo;
  String? error;
  bool busy = false;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    refresh();
  }

  Future<void> refresh() async {
    try {
      final value = await channel.invokeMapMethod<String, dynamic>('status');
      if (mounted) setState(() => status = value ?? {});
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message);
    } on MissingPluginException {
      if (mounted) setState(() => error = 'Android 실기기에서 사용할 수 있습니다.');
    }
  }

  Future<void> execute(String method) async {
    setState(() {
      busy = true;
      error = null;
      result = null;
    });
    timer = Timer.periodic(const Duration(seconds: 2), (_) => refresh());
    try {
      final value = await channel.invokeMapMethod<String, dynamic>(
        method,
        method == 'analyze' ? {'path': photo} : null,
      );
      if (mounted && method == 'analyze') setState(() => result = value);
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message ?? '작업에 실패했습니다.');
    } finally {
      timer?.cancel();
      await refresh();
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> pickPhoto() async {
    try {
      final image = await ImagePicker().pickImage(
        source: ImageSource.gallery,
        maxWidth: 512,
        maxHeight: 512,
        imageQuality: 85,
        requestFullMetadata: false,
      );
      if (mounted && image != null) {
        setState(() {
          photo = image.path;
          result = null;
          error = null;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message);
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ready = status['ready'] == true;
    final working = busy || status['busy'] == true;
    final progress =
        ((status['copiedBytes'] as num? ?? 0) /
                (status['totalBytes'] as num? ?? 2588147712))
            .clamp(0.0, 1.0);
    return PopScope(
      canPop: !working,
      child: Scaffold(
        appBar: AppBar(title: const Text('기기 내 음식 분석 · 실험')),
        body: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const Text(
              'Gemma 4 E2B',
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 12),
            const Text(
              '선택한 사진을 휴대폰 내부에서 분석하고 음식명과 실행 시간을 확인합니다. 일반 식단 분석에 적용하기 전 성능을 확인하는 개발자 기능입니다.',
            ),
            const SizedBox(height: 16),
            Text(
              ready
                  ? '모델 검증 완료 · 약 2.59GB'
                  : 'gemma-4-E2B-it.litertlm 파일을 선택해 주세요.',
            ),
            OutlinedButton(
              onPressed: working ? null : () => execute('importModel'),
              child: const Text('모델 파일 불러오기'),
            ),
            Text(
              status['modelLoaded'] == true
                  ? '모델이 메모리에 준비되어 있습니다. 다음 분석에서 재사용합니다.'
                  : '첫 분석 시 모델을 초기화합니다.',
            ),
            if (status['modelLoaded'] == true)
              OutlinedButton(
                onPressed: working ? null : () => execute('releaseModel'),
                child: const Text('모델 메모리 해제'),
              ),
            OutlinedButton(
              onPressed: working ? null : pickPhoto,
              child: const Text('갤러리에서 사진 선택'),
            ),
            if (photo != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Image.file(
                  File(photo!),
                  height: 220,
                  fit: BoxFit.contain,
                ),
              ),
            FilledButton(
              onPressed: working || !ready || photo == null
                  ? null
                  : () => execute('analyze'),
              child: const Text('휴대폰에서 분석'),
            ),
            if (working) ...[
              const SizedBox(height: 16),
              LinearProgressIndicator(
                value: status['stage'] == '모델 복사 및 SHA256 검증' ? progress : null,
              ),
              Text(status['stage']?.toString() ?? '준비 중'),
              const Text('모델 초기화와 분석에는 시간이 걸릴 수 있습니다. 완료될 때까지 화면을 유지해 주세요.'),
            ],
            if (error != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(
                  error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            if (result != null) ...[
              const SizedBox(height: 20),
              Text(
                (result!['foods'] as List).join(', '),
                style: const TextStyle(fontSize: 22),
              ),
              Text(
                result!['engineReused'] == true
                    ? '준비된 모델 재사용 · 초기화 생략'
                    : '모델 최초 초기화',
              ),
              Text(
                '초기화: ${((result!['initializationMs'] as num) / 1000).toStringAsFixed(2)}초',
              ),
              Text(
                '분석: ${((result!['inferenceMs'] as num) / 1000).toStringAsFixed(2)}초',
              ),
              Text(
                '분석 직후 앱 전체 메모리(PSS): ${(result!['processPssMbAfter'] as num).toStringAsFixed(0)}MB',
              ),
              Text('${result!['runtime']} · ${result!['backend']}'),
            ],
          ],
        ),
      ),
    );
  }
}
