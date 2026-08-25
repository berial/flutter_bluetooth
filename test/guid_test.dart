import 'package:flutter_bluetooth/flutter_bluetooth.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('Guid Tests', () {
    test('Guid creation from 128-bit string', () {
      const uuidStr = '00001800-0000-1000-8000-00805f9b34fb';
      final guid = Guid(uuidStr);
      expect(guid.str, equals(uuidStr));
      expect(guid.toString(), equals('Guid($uuidStr)'));
    });

    test('Guid.short creates 128-bit UUID correctly', () {
      final guid1800 = Guid.short('1800');
      expect(guid1800.str, equals('00001800-0000-1000-8000-00805f9b34fb'));

      final guidFFE0 = Guid.short('ffe0');
      expect(guidFFE0.str, equals('0000ffe0-0000-1000-8000-00805f9b34fb'));

      final guidWithHyphens = Guid.short('ff-e1');
      expect(guidWithHyphens.str, equals('0000ffe1-0000-1000-8000-00805f9b34fb'));
    });

    test('Guid.fromString normalizes to lowercase', () {
      final guid = Guid.fromString('0000FFE0-0000-1000-8000-00805F9B34FB');
      expect(guid.str, equals('0000ffe0-0000-1000-8000-00805f9b34fb'));
    });

    test('Guid equality and hashCode', () {
      final guid1 = Guid('00001800-0000-1000-8000-00805f9b34fb');
      final guid2 = Guid.short('1800');
      final guid3 = Guid.short('1801');

      expect(guid1, equals(guid2));
      expect(guid1.hashCode, equals(guid2.hashCode));
      expect(guid1, isNot(equals(guid3)));
    });
  });
}
