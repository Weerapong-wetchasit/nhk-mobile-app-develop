import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:nhkcoop/data/services/local/device_key_format.dart';
import 'package:nhkcoop/data/services/local/device_key_service.dart';

/// On-device round-trip for the `nhk/device_key` channel. Exercises the real
/// AndroidKeyStore / iOS Keychain, so it must run on a device or emulator:
///
///   flutter test integration_test/device_key_service_test.dart
///
/// It guards the crypto contract (`docs/pin-migration/migration-plan.md` §7):
/// the exported public key is a valid P-256 SPKI and the signature is RFC 3279
/// DER — the one encoding mistake that otherwise fails `verify` silently.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  final service = DeviceKeyServiceImpl();
  const alias = 'integration-test-uid';

  tearDown(() async {
    await service.deleteKey(alias);
  });

  testWidgets('generate → key present → public key is P-256 SPKI', (_) async {
    final publicKey = await service.generateKeyPair(alias);

    expect(DeviceKeyFormat.isP256Spki(base64Decode(publicKey)), isTrue);
    expect(await service.hasKey(alias), isTrue);
    expect(await service.getPublicKey(alias), publicKey);
  });

  testWidgets('sign produces a DER ECDSA signature over the nonce', (_) async {
    await service.generateKeyPair(alias);

    // A 32-byte nonce, as the server delivers it (base64).
    final nonce = base64Encode(List<int>.generate(32, (i) => i));
    final signature = await service.sign(alias, nonce);

    // sign() already asserts DER internally; re-check here so a regression in
    // that guard still fails the round-trip explicitly.
    expect(DeviceKeyFormat.isDerEcdsaSignature(base64Decode(signature)), isTrue);
  });

  testWidgets('different nonces yield different signatures', (_) async {
    await service.generateKeyPair(alias);

    final sigA = await service.sign(alias, base64Encode(List.filled(32, 1)));
    final sigB = await service.sign(alias, base64Encode(List.filled(32, 2)));

    expect(sigA, isNot(equals(sigB)));
  });

  testWidgets('deleteKey removes the key', (_) async {
    await service.generateKeyPair(alias);
    expect(await service.hasKey(alias), isTrue);

    await service.deleteKey(alias);

    expect(await service.hasKey(alias), isFalse);
    expect(await service.getPublicKey(alias), isNull);
  });
}
