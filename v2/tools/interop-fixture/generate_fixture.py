#!/usr/bin/env python3
"""Generate independent otpauth-migration interoperability fixtures.

This script uses `protoc` + the Python `google.protobuf` runtime — an
INDEPENDENT protobuf implementation — to build frozen fixtures from the
confirmed `MigrationPayload` schema (google_auth_migration.proto). It does NOT
use this project's MinimalProtobuf decoder or ProtoFixture builder, so the
resulting bytes are a genuine cross-implementation interoperability check.

The produced fixtures are TEST-ONLY and contain only disposable synthetic
secrets (never real credentials).

Usage:
    protoc --python_out=. google_auth_migration.proto
    python3 generate_fixture.py

Output:
    Prints the serialized payload (hex), its standard (padded) Base64 and
    URL-safe no-padding Base64 forms, plus the expected decoded values that
    `InteropFixtures.PROTOC_FIXTURE_*` constants lock down in
    core/src/test/kotlin/com/rescueauth/v2/migration/InteropFixtures.kt.
"""
import base64

import google_auth_migration_pb2 as pb


def b32(b: bytes) -> str:
    return base64.b32encode(b).decode().rstrip("=")


def main() -> None:
    payload = pb.MigrationPayload()
    payload.version = 1
    payload.batch_size = 3
    payload.batch_index = 1
    payload.batch_id = 424242

    e1 = payload.otp_parameters.add()
    e1.secret = bytes.fromhex("48656c6c0f21deadbee8")
    e1.name = "fixture-a@example.com"
    e1.issuer = "FixtureIssuer"
    e1.algorithm = pb.MigrationPayload.ALGORITHM_SHA1
    e1.digits = pb.MigrationPayload.DIGIT_COUNT_SIX
    e1.type = pb.MigrationPayload.OTP_TYPE_TOTP

    e2 = payload.otp_parameters.add()
    e2.secret = bytes.fromhex("3d63c114e02bac5318a03481d4d668631e6f1993")
    e2.name = "fixture-b@example.com"
    e2.issuer = "FixtureSha512"
    e2.algorithm = pb.MigrationPayload.ALGORITHM_SHA512
    e2.digits = pb.MigrationPayload.DIGIT_COUNT_EIGHT
    e2.type = pb.MigrationPayload.OTP_TYPE_TOTP

    e3 = payload.otp_parameters.add()
    e3.secret = bytes.fromhex("00448d6c642f3be31d1f")
    e3.name = "fixture-hotp@example.com"
    e3.issuer = "FixtureHotp"
    e3.algorithm = pb.MigrationPayload.ALGORITHM_SHA1
    e3.digits = pb.MigrationPayload.DIGIT_COUNT_SIX
    e3.type = pb.MigrationPayload.OTP_TYPE_HOTP
    e3.counter = 7

    raw = payload.SerializeToString()
    print("=== serialized bytes (%d) ===" % len(raw))
    print(raw.hex())
    print()
    print("=== standard base64 (padded) ===")
    print(base64.b64encode(raw).decode())
    print()
    print("=== urlsafe base64 (no padding) ===")
    print(base64.urlsafe_b64encode(raw).decode().rstrip("="))
    print()
    print("=== expected decoded values ===")
    for e in payload.otp_parameters:
        print(
            "name=%r issuer=%r algorithm=%d digits=%d type=%d counter=%d secret_b32=%s"
            % (e.name, e.issuer, e.algorithm, e.digits, e.type, e.counter, b32(bytes(e.secret)))
        )
    print("batch_size=%d batch_index=%d batch_id=%d version=%d"
          % (payload.batch_size, payload.batch_index, payload.batch_id, payload.version))


if __name__ == "__main__":
    main()
