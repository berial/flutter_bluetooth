import 'package:flutter_bluetooth/flutter_bluetooth.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('OperationQueue Tests', () {
    test('Tasks execute serially in order', () async {
      final queue = OperationQueue();
      final executionOrder = <int>[];

      final future1 = queue.enqueue<String>(() async {
        await Future.delayed(const Duration(milliseconds: 30));
        executionOrder.add(1);
        return 'first';
      });

      final future2 = queue.enqueue<String>(() async {
        await Future.delayed(const Duration(milliseconds: 10));
        executionOrder.add(2);
        return 'second';
      });

      final future3 = queue.enqueue<String>(() async {
        executionOrder.add(3);
        return 'third';
      });

      final results = await Future.wait([future1, future2, future3]);

      expect(results, equals(['first', 'second', 'third']));
      expect(executionOrder, equals([1, 2, 3]));
    });

    test('Errors in prior tasks do not block subsequent tasks', () async {
      final queue = OperationQueue();
      final executionOrder = <int>[];

      final future1 = queue.enqueue<String>(() async {
        await Future.delayed(const Duration(milliseconds: 10));
        executionOrder.add(1);
        throw Exception('Task 1 failed');
      });

      final future2 = queue.enqueue<String>(() async {
        executionOrder.add(2);
        return 'second';
      });

      expect(future1, throwsA(isA<Exception>()));
      final result2 = await future2;

      expect(result2, equals('second'));
      expect(executionOrder, equals([1, 2]));
    });

    test('Clear resets the queue tail', () async {
      final queue = OperationQueue();
      queue.clear();
      final result = await queue.enqueue(() async => 'cleared_ok');
      expect(result, equals('cleared_ok'));
    });
  });
}
