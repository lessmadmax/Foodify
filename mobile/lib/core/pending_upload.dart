import 'dart:convert';
import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

class PendingUpload {
  final String key, member;
  final int eatenAt;
  final List<String> paths;
  final Map<String, dynamic> captureInfo;
  PendingUpload(
    this.key,
    this.member,
    this.eatenAt,
    this.paths, [
    this.captureInfo = const {
      'method': 'guided_photos',
      'volumeValidated': false,
    },
  ]);
  static Future<Directory> _directory() async {
    final root = await getApplicationDocumentsDirectory();
    return Directory('${root.path}/pending').create(recursive: true);
  }

  static Future<PendingUpload> save(
    String member,
    List<String> sources, {
    Map<String, dynamic>? captureInfo,
  }) async {
    final dir = await _directory();
    final key = const Uuid().v4();
    final paths = <String>[];
    for (var i = 0; i < sources.length; i++) {
      paths.add((await File(sources[i]).copy('${dir.path}/$key-$i.jpg')).path);
    }
    final upload = PendingUpload(
      key,
      member,
      DateTime.now().millisecondsSinceEpoch,
      paths,
      captureInfo ??
          const {'method': 'guided_photos', 'volumeValidated': false},
    );
    await File('${dir.path}/$key.json').writeAsString(
      jsonEncode({
        'key': key,
        'member': member,
        'eatenAt': upload.eatenAt,
        'paths': paths,
        'captureInfo': upload.captureInfo,
      }),
    );
    return upload;
  }

  static Future<List<PendingUpload>> load(String member) async {
    final dir = await _directory();
    final result = <PendingUpload>[];
    await for (final entry in dir.list()) {
      if (!entry.path.endsWith('.json')) continue;
      final data = jsonDecode(await File(entry.path).readAsString());
      if (data['member'] == member) {
        result.add(
          PendingUpload(
            data['key'],
            member,
            data['eatenAt'],
            List<String>.from(data['paths']),
            Map<String, dynamic>.from(
              data['captureInfo'] ??
                  {'method': 'guided_photos', 'volumeValidated': false},
            ),
          ),
        );
      }
    }
    return result;
  }

  Future<void> remove() async {
    for (final p in paths) {
      final f = File(p);
      if (await f.exists()) await f.delete();
    }
    final dir = await _directory();
    final metadata = File('${dir.path}/$key.json');
    if (await metadata.exists()) await metadata.delete();
  }
}
