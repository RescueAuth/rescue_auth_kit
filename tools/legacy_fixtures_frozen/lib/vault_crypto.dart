import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

const String rescueAuthKitVaultMagic = 'RescueAuthKitVault';
const int rescueAuthKitVaultVersion = 1;

const String rescueAuthKitKdfArgon2id = 'argon2id';
const String rescueAuthKitCipherXchacha20Poly1305 = 'xchacha20poly1305';

const int defaultKdfSaltLengthBytes = 16;

const int defaultArgon2MemoryKiB = 19456; // 19 MB
const int defaultArgon2Iterations = 2;
const int defaultArgon2Parallelism = 1;
const int defaultArgon2HashLengthBytes = 32;

String _b64e(List<int> bytes) => base64UrlEncode(bytes);
Uint8List _b64d(String s) => Uint8List.fromList(base64Url.decode(s));

class VaultKdfParams {
  final String name;
  final int memoryKiB;
  final int iterations;
  final int parallelism;
  final int hashLengthBytes;
  final Uint8List salt;

  const VaultKdfParams({
    required this.name,
    required this.memoryKiB,
    required this.iterations,
    required this.parallelism,
    required this.hashLengthBytes,
    required this.salt,
  });

  factory VaultKdfParams.defaultParams({required Uint8List salt}) {
    return VaultKdfParams(
      name: rescueAuthKitKdfArgon2id,
      memoryKiB: defaultArgon2MemoryKiB,
      iterations: defaultArgon2Iterations,
      parallelism: defaultArgon2Parallelism,
      hashLengthBytes: defaultArgon2HashLengthBytes,
      salt: salt,
    );
  }

  factory VaultKdfParams.forTesting({required Uint8List salt}) {
    return VaultKdfParams(
      name: rescueAuthKitKdfArgon2id,
      memoryKiB: 1024, // 1 MB
      iterations: 1,
      parallelism: 1,
      hashLengthBytes: defaultArgon2HashLengthBytes,
      salt: salt,
    );
  }

  Map<String, dynamic> toJson() => <String, dynamic>{
    'name': name,
    'memoryKiB': memoryKiB,
    'iterations': iterations,
    'parallelism': parallelism,
    'hashLengthBytes': hashLengthBytes,
    'saltB64': _b64e(salt),
  };

  factory VaultKdfParams.fromJson(Map<String, dynamic> json) {
    final name = json['name'] as String?;
    final memoryKiB = json['memoryKiB'] as int?;
    final iterations = json['iterations'] as int?;
    final parallelism = json['parallelism'] as int?;
    final hashLengthBytes = json['hashLengthBytes'] as int?;
    final saltB64 = json['saltB64'] as String?;

    if (name == null ||
        memoryKiB == null ||
        iterations == null ||
        parallelism == null ||
        hashLengthBytes == null ||
        saltB64 == null) {
      throw const FormatException('Invalid KDF parameters JSON');
    }

    return VaultKdfParams(
      name: name,
      memoryKiB: memoryKiB,
      iterations: iterations,
      parallelism: parallelism,
      hashLengthBytes: hashLengthBytes,
      salt: _b64d(saltB64),
    );
  }
}

class VaultFile {
  final int version;
  final VaultKdfParams kdf;
  final String cipher;

  final Uint8List nonce;
  final Uint8List mac;
  final Uint8List ciphertext;

  const VaultFile({
    required this.version,
    required this.kdf,
    required this.cipher,
    required this.nonce,
    required this.mac,
    required this.ciphertext,
  });

  Map<String, dynamic> toJson() => <String, dynamic>{
    'magic': rescueAuthKitVaultMagic,
    'version': version,
    'kdf': kdf.toJson(),
    'cipher': cipher,
    'nonceB64': _b64e(nonce),
    'macB64': _b64e(mac),
    'ciphertextB64': _b64e(ciphertext),
  };

  factory VaultFile.fromJson(Map<String, dynamic> json) {
    final magic = json['magic'] as String?;
    final version = json['version'] as int?;
    final kdfJson = json['kdf'] as Map<String, dynamic>?;
    final cipher = json['cipher'] as String?;
    final nonceB64 = json['nonceB64'] as String?;
    final macB64 = json['macB64'] as String?;
    final ciphertextB64 = json['ciphertextB64'] as String?;

    if (magic != rescueAuthKitVaultMagic) {
      throw const FormatException('Not a RescueAuthKit vault file');
    }
    if (version == null || version < 1) {
      throw const FormatException('Missing/invalid vault version');
    }
    if (kdfJson == null ||
        cipher == null ||
        nonceB64 == null ||
        macB64 == null ||
        ciphertextB64 == null) {
      throw const FormatException('Missing required fields');
    }

    return VaultFile(
      version: version,
      kdf: VaultKdfParams.fromJson(kdfJson),
      cipher: cipher,
      nonce: _b64d(nonceB64),
      mac: _b64d(macB64),
      ciphertext: _b64d(ciphertextB64),
    );
  }

  Uint8List encode() => Uint8List.fromList(utf8.encode(jsonEncode(toJson())));

  static VaultFile decode(Uint8List bytes) {
    final decoded = jsonDecode(utf8.decode(bytes)) as Object?;
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Vault file is not a JSON object');
    }
    return VaultFile.fromJson(decoded);
  }
}

class VaultCrypto {
  final Cipher cipher;
  final Random random;

  VaultCrypto({Cipher? cipher, Random? random})
    : cipher = cipher ?? Cryptography.instance.xchacha20Poly1305Aead(),
      random = random ?? Random.secure();

  Uint8List newSalt([int length = defaultKdfSaltLengthBytes]) {
    return Uint8List.fromList(
      List<int>.generate(length, (_) => random.nextInt(256)),
    );
  }

  Future<SecretKey> deriveKeyFromPassword({
    required String password,
    required VaultKdfParams params,
  }) async {
    if (params.name != rescueAuthKitKdfArgon2id) {
      throw UnsupportedError('Unsupported KDF: ${params.name}');
    }
    final kdf = Argon2id(
      memory: params.memoryKiB,
      iterations: params.iterations,
      parallelism: params.parallelism,
      hashLength: params.hashLengthBytes,
    );
    return kdf.deriveKeyFromPassword(password: password, nonce: params.salt);
  }

  Future<VaultFile> encryptToFile({
    required Uint8List cleartextJsonBytes,
    required SecretKey key,
    required VaultKdfParams kdfParams,
  }) async {
    final box = await cipher.encrypt(cleartextJsonBytes, secretKey: key);
    return VaultFile(
      version: rescueAuthKitVaultVersion,
      kdf: kdfParams,
      cipher: rescueAuthKitCipherXchacha20Poly1305,
      nonce: Uint8List.fromList(box.nonce),
      mac: Uint8List.fromList(box.mac.bytes),
      ciphertext: Uint8List.fromList(box.cipherText),
    );
  }

  Future<Uint8List> decryptFile({
    required VaultFile file,
    required SecretKey key,
  }) async {
    final box = SecretBox(
      file.ciphertext,
      nonce: file.nonce,
      mac: Mac(file.mac),
    );
    final clear = await cipher.decrypt(box, secretKey: key);
    return Uint8List.fromList(clear);
  }
}
