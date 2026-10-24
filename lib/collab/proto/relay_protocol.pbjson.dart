// This is a generated file - do not edit.
//
// Generated from relay_protocol.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports
// ignore_for_file: unused_import

import 'dart:convert' as $convert;
import 'dart:core' as $core;
import 'dart:typed_data' as $typed_data;

@$core.Deprecated('Use actionTypeDescriptor instead')
const ActionType$json = {
  '1': 'ActionType',
  '2': [
    {'1': 'PROPOSE', '2': 0},
    {'1': 'ACCEPT', '2': 1},
    {'1': 'REJECT', '2': 2},
    {'1': 'PATCH_TIME', '2': 3},
    {'1': 'REASSIGN', '2': 4},
    {'1': 'MAKE_OPTIONAL', '2': 5},
    {'1': 'PRUNE', '2': 6},
    {'1': 'CANCEL', '2': 7},
    {'1': 'REOPEN', '2': 8},
    {'1': 'VOTE', '2': 9},
    {'1': 'ADD_SLOT', '2': 10},
    {'1': 'FORCE_FINALIZE', '2': 11},
    {'1': 'NUDGE', '2': 12},
    {'1': 'BYPASS', '2': 13},
    {'1': 'REKEY', '2': 14},
    {'1': 'MEMBER_REMOVED', '2': 15},
    {'1': 'TRANSFER_INITIATOR', '2': 16},
    {'1': 'BRIEFING_UPDATE', '2': 17},
    {'1': 'STEP_NOTE', '2': 18},
    {'1': 'ATTACH_FACT', '2': 19},
    {'1': 'UPDATE_FACT', '2': 20},
    {'1': 'DETACH_FACT', '2': 21},
    {'1': 'FREEMASK_QUERY', '2': 22},
    {'1': 'FREEMASK_RESPONSE', '2': 23},
    {'1': 'DELIVERY_ACK', '2': 24},
    {'1': 'CLIENT_ACK', '2': 25},
    {'1': 'PING', '2': 26},
    {'1': 'ADD_MEMBER', '2': 27},
    {'1': 'PATCH_STEP_TIME', '2': 28},
    {'1': 'LEAVE', '2': 29},
  ],
};

/// Descriptor for `ActionType`. Decode as a `google.protobuf.EnumDescriptorProto`.
final $typed_data.Uint8List actionTypeDescriptor = $convert.base64Decode(
    'CgpBY3Rpb25UeXBlEgsKB1BST1BPU0UQABIKCgZBQ0NFUFQQARIKCgZSRUpFQ1QQAhIOCgpQQV'
    'RDSF9USU1FEAMSDAoIUkVBU1NJR04QBBIRCg1NQUtFX09QVElPTkFMEAUSCQoFUFJVTkUQBhIK'
    'CgZDQU5DRUwQBxIKCgZSRU9QRU4QCBIICgRWT1RFEAkSDAoIQUREX1NMT1QQChISCg5GT1JDRV'
    '9GSU5BTElaRRALEgkKBU5VREdFEAwSCgoGQllQQVNTEA0SCQoFUkVLRVkQDhISCg5NRU1CRVJf'
    'UkVNT1ZFRBAPEhYKElRSQU5TRkVSX0lOSVRJQVRPUhAQEhMKD0JSSUVGSU5HX1VQREFURRAREg'
    '0KCVNURVBfTk9URRASEg8KC0FUVEFDSF9GQUNUEBMSDwoLVVBEQVRFX0ZBQ1QQFBIPCgtERVRB'
    'Q0hfRkFDVBAVEhIKDkZSRUVNQVNLX1FVRVJZEBYSFQoRRlJFRU1BU0tfUkVTUE9OU0UQFxIQCg'
    'xERUxJVkVSWV9BQ0sQGBIOCgpDTElFTlRfQUNLEBkSCAoEUElORxAaEg4KCkFERF9NRU1CRVIQ'
    'GxITCg9QQVRDSF9TVEVQX1RJTUUQHBIJCgVMRUFWRRAd');

@$core.Deprecated('Use envelopeDescriptor instead')
const Envelope$json = {
  '1': 'Envelope',
  '2': [
    {'1': 'bundle_id', '3': 1, '4': 1, '5': 9, '10': 'bundleId'},
    {
      '1': 'action',
      '3': 2,
      '4': 1,
      '5': 14,
      '6': '.shiguang.relay.v1.ActionType',
      '10': 'action'
    },
    {'1': 'seq_id', '3': 3, '4': 1, '5': 3, '10': 'seqId'},
    {'1': 'sender_hint', '3': 4, '4': 1, '5': 9, '10': 'senderHint'},
    {'1': 'cipher', '3': 5, '4': 1, '5': 12, '10': 'cipher'},
    {'1': 'client_msg_id', '3': 6, '4': 1, '5': 9, '10': 'clientMsgId'},
    {'1': 'client_ts', '3': 7, '4': 1, '5': 3, '10': 'clientTs'},
    {'1': 'topology_ref', '3': 8, '4': 1, '5': 9, '10': 'topologyRef'},
    {'1': 'key_ver', '3': 9, '4': 1, '5': 5, '10': 'keyVer'},
    {
      '1': 'member_key_envelopes',
      '3': 10,
      '4': 3,
      '5': 11,
      '6': '.shiguang.relay.v1.MemberKeyEnvelope',
      '10': 'memberKeyEnvelopes'
    },
  ],
};

/// Descriptor for `Envelope`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List envelopeDescriptor = $convert.base64Decode(
    'CghFbnZlbG9wZRIbCglidW5kbGVfaWQYASABKAlSCGJ1bmRsZUlkEjUKBmFjdGlvbhgCIAEoDj'
    'IdLnNoaWd1YW5nLnJlbGF5LnYxLkFjdGlvblR5cGVSBmFjdGlvbhIVCgZzZXFfaWQYAyABKANS'
    'BXNlcUlkEh8KC3NlbmRlcl9oaW50GAQgASgJUgpzZW5kZXJIaW50EhYKBmNpcGhlchgFIAEoDF'
    'IGY2lwaGVyEiIKDWNsaWVudF9tc2dfaWQYBiABKAlSC2NsaWVudE1zZ0lkEhsKCWNsaWVudF90'
    'cxgHIAEoA1IIY2xpZW50VHMSIQoMdG9wb2xvZ3lfcmVmGAggASgJUgt0b3BvbG9neVJlZhIXCg'
    'drZXlfdmVyGAkgASgFUgZrZXlWZXISVgoUbWVtYmVyX2tleV9lbnZlbG9wZXMYCiADKAsyJC5z'
    'aGlndWFuZy5yZWxheS52MS5NZW1iZXJLZXlFbnZlbG9wZVISbWVtYmVyS2V5RW52ZWxvcGVz');

@$core.Deprecated('Use memberKeyEnvelopeDescriptor instead')
const MemberKeyEnvelope$json = {
  '1': 'MemberKeyEnvelope',
  '2': [
    {'1': 'recipient_user_id', '3': 1, '4': 1, '5': 9, '10': 'recipientUserId'},
    {'1': 'wrapped_key', '3': 2, '4': 1, '5': 12, '10': 'wrappedKey'},
    {'1': 'key_ver', '3': 3, '4': 1, '5': 5, '10': 'keyVer'},
  ],
};

/// Descriptor for `MemberKeyEnvelope`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List memberKeyEnvelopeDescriptor = $convert.base64Decode(
    'ChFNZW1iZXJLZXlFbnZlbG9wZRIqChFyZWNpcGllbnRfdXNlcl9pZBgBIAEoCVIPcmVjaXBpZW'
    '50VXNlcklkEh8KC3dyYXBwZWRfa2V5GAIgASgMUgp3cmFwcGVkS2V5EhcKB2tleV92ZXIYAyAB'
    'KAVSBmtleVZlcg==');

@$core.Deprecated('Use authChallengeDescriptor instead')
const AuthChallenge$json = {
  '1': 'AuthChallenge',
  '2': [
    {'1': 'nonce', '3': 1, '4': 1, '5': 9, '10': 'nonce'},
    {'1': 'pow_challenge', '3': 2, '4': 1, '5': 9, '10': 'powChallenge'},
    {'1': 'pow_difficulty', '3': 3, '4': 1, '5': 5, '10': 'powDifficulty'},
    {'1': 'expires_at', '3': 4, '4': 1, '5': 3, '10': 'expiresAt'},
  ],
};

/// Descriptor for `AuthChallenge`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List authChallengeDescriptor = $convert.base64Decode(
    'Cg1BdXRoQ2hhbGxlbmdlEhQKBW5vbmNlGAEgASgJUgVub25jZRIjCg1wb3dfY2hhbGxlbmdlGA'
    'IgASgJUgxwb3dDaGFsbGVuZ2USJQoOcG93X2RpZmZpY3VsdHkYAyABKAVSDXBvd0RpZmZpY3Vs'
    'dHkSHQoKZXhwaXJlc19hdBgEIAEoA1IJZXhwaXJlc0F0');

@$core.Deprecated('Use registerRequestDescriptor instead')
const RegisterRequest$json = {
  '1': 'RegisterRequest',
  '2': [
    {
      '1': 'user_id',
      '3': 1,
      '4': 1,
      '5': 9,
      '8': {'3': true},
      '10': 'userId',
    },
    {'1': 'identity_key_pub', '3': 2, '4': 1, '5': 12, '10': 'identityKeyPub'},
    {
      '1': 'signed_prekey_pub',
      '3': 3,
      '4': 1,
      '5': 12,
      '10': 'signedPrekeyPub'
    },
    {
      '1': 'signed_prekey_sig',
      '3': 4,
      '4': 1,
      '5': 12,
      '10': 'signedPrekeySig'
    },
    {'1': 'push_token', '3': 5, '4': 1, '5': 9, '10': 'pushToken'},
    {
      '1': 'encrypted_profile',
      '3': 6,
      '4': 1,
      '5': 12,
      '10': 'encryptedProfile'
    },
    {
      '1': 'signed_pop',
      '3': 7,
      '4': 1,
      '5': 12,
      '8': {'3': true},
      '10': 'signedPop',
    },
    {'1': 'pow_solution', '3': 8, '4': 1, '5': 9, '10': 'powSolution'},
    {'1': 'username', '3': 9, '4': 1, '5': 9, '10': 'username'},
    {'1': 'password', '3': 10, '4': 1, '5': 9, '10': 'password'},
  ],
};

/// Descriptor for `RegisterRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List registerRequestDescriptor = $convert.base64Decode(
    'Cg9SZWdpc3RlclJlcXVlc3QSGwoHdXNlcl9pZBgBIAEoCUICGAFSBnVzZXJJZBIoChBpZGVudG'
    'l0eV9rZXlfcHViGAIgASgMUg5pZGVudGl0eUtleVB1YhIqChFzaWduZWRfcHJla2V5X3B1YhgD'
    'IAEoDFIPc2lnbmVkUHJla2V5UHViEioKEXNpZ25lZF9wcmVrZXlfc2lnGAQgASgMUg9zaWduZW'
    'RQcmVrZXlTaWcSHQoKcHVzaF90b2tlbhgFIAEoCVIJcHVzaFRva2VuEisKEWVuY3J5cHRlZF9w'
    'cm9maWxlGAYgASgMUhBlbmNyeXB0ZWRQcm9maWxlEiEKCnNpZ25lZF9wb3AYByABKAxCAhgBUg'
    'lzaWduZWRQb3ASIQoMcG93X3NvbHV0aW9uGAggASgJUgtwb3dTb2x1dGlvbhIaCgh1c2VybmFt'
    'ZRgJIAEoCVIIdXNlcm5hbWUSGgoIcGFzc3dvcmQYCiABKAlSCHBhc3N3b3Jk');

@$core.Deprecated('Use loginRequestDescriptor instead')
const LoginRequest$json = {
  '1': 'LoginRequest',
  '2': [
    {
      '1': 'user_id',
      '3': 1,
      '4': 1,
      '5': 9,
      '8': {'3': true},
      '10': 'userId',
    },
    {
      '1': 'identity_key_pub',
      '3': 2,
      '4': 1,
      '5': 12,
      '8': {'3': true},
      '10': 'identityKeyPub',
    },
    {
      '1': 'signed_pop',
      '3': 3,
      '4': 1,
      '5': 12,
      '8': {'3': true},
      '10': 'signedPop',
    },
    {
      '1': 'pow_solution',
      '3': 4,
      '4': 1,
      '5': 9,
      '8': {'3': true},
      '10': 'powSolution',
    },
    {'1': 'username', '3': 5, '4': 1, '5': 9, '10': 'username'},
    {'1': 'password', '3': 6, '4': 1, '5': 9, '10': 'password'},
  ],
};

/// Descriptor for `LoginRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List loginRequestDescriptor = $convert.base64Decode(
    'CgxMb2dpblJlcXVlc3QSGwoHdXNlcl9pZBgBIAEoCUICGAFSBnVzZXJJZBIsChBpZGVudGl0eV'
    '9rZXlfcHViGAIgASgMQgIYAVIOaWRlbnRpdHlLZXlQdWISIQoKc2lnbmVkX3BvcBgDIAEoDEIC'
    'GAFSCXNpZ25lZFBvcBIlCgxwb3dfc29sdXRpb24YBCABKAlCAhgBUgtwb3dTb2x1dGlvbhIaCg'
    'h1c2VybmFtZRgFIAEoCVIIdXNlcm5hbWUSGgoIcGFzc3dvcmQYBiABKAlSCHBhc3N3b3Jk');

@$core.Deprecated('Use changePasswordRequestDescriptor instead')
const ChangePasswordRequest$json = {
  '1': 'ChangePasswordRequest',
  '2': [
    {'1': 'old_password', '3': 1, '4': 1, '5': 9, '10': 'oldPassword'},
    {'1': 'new_password', '3': 2, '4': 1, '5': 9, '10': 'newPassword'},
  ],
};

/// Descriptor for `ChangePasswordRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List changePasswordRequestDescriptor = $convert.base64Decode(
    'ChVDaGFuZ2VQYXNzd29yZFJlcXVlc3QSIQoMb2xkX3Bhc3N3b3JkGAEgASgJUgtvbGRQYXNzd2'
    '9yZBIhCgxuZXdfcGFzc3dvcmQYAiABKAlSC25ld1Bhc3N3b3Jk');

@$core.Deprecated('Use lookupUserResponseDescriptor instead')
const LookupUserResponse$json = {
  '1': 'LookupUserResponse',
  '2': [
    {'1': 'user_id', '3': 1, '4': 1, '5': 9, '10': 'userId'},
    {'1': 'username', '3': 2, '4': 1, '5': 9, '10': 'username'},
    {'1': 'identity_key_pub', '3': 3, '4': 1, '5': 12, '10': 'identityKeyPub'},
    {
      '1': 'signed_prekey_pub',
      '3': 4,
      '4': 1,
      '5': 12,
      '10': 'signedPrekeyPub'
    },
    {
      '1': 'signed_prekey_sig',
      '3': 5,
      '4': 1,
      '5': 12,
      '10': 'signedPrekeySig'
    },
  ],
};

/// Descriptor for `LookupUserResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List lookupUserResponseDescriptor = $convert.base64Decode(
    'ChJMb29rdXBVc2VyUmVzcG9uc2USFwoHdXNlcl9pZBgBIAEoCVIGdXNlcklkEhoKCHVzZXJuYW'
    '1lGAIgASgJUgh1c2VybmFtZRIoChBpZGVudGl0eV9rZXlfcHViGAMgASgMUg5pZGVudGl0eUtl'
    'eVB1YhIqChFzaWduZWRfcHJla2V5X3B1YhgEIAEoDFIPc2lnbmVkUHJla2V5UHViEioKEXNpZ2'
    '5lZF9wcmVrZXlfc2lnGAUgASgMUg9zaWduZWRQcmVrZXlTaWc=');

@$core.Deprecated('Use authResponseDescriptor instead')
const AuthResponse$json = {
  '1': 'AuthResponse',
  '2': [
    {'1': 'access_token', '3': 1, '4': 1, '5': 9, '10': 'accessToken'},
    {'1': 'refresh_token', '3': 2, '4': 1, '5': 9, '10': 'refreshToken'},
    {'1': 'access_expires_at', '3': 3, '4': 1, '5': 3, '10': 'accessExpiresAt'},
  ],
};

/// Descriptor for `AuthResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List authResponseDescriptor = $convert.base64Decode(
    'CgxBdXRoUmVzcG9uc2USIQoMYWNjZXNzX3Rva2VuGAEgASgJUgthY2Nlc3NUb2tlbhIjCg1yZW'
    'ZyZXNoX3Rva2VuGAIgASgJUgxyZWZyZXNoVG9rZW4SKgoRYWNjZXNzX2V4cGlyZXNfYXQYAyAB'
    'KANSD2FjY2Vzc0V4cGlyZXNBdA==');

@$core.Deprecated('Use refreshRequestDescriptor instead')
const RefreshRequest$json = {
  '1': 'RefreshRequest',
  '2': [
    {'1': 'refresh_token', '3': 1, '4': 1, '5': 9, '10': 'refreshToken'},
  ],
};

/// Descriptor for `RefreshRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List refreshRequestDescriptor = $convert.base64Decode(
    'Cg5SZWZyZXNoUmVxdWVzdBIjCg1yZWZyZXNoX3Rva2VuGAEgASgJUgxyZWZyZXNoVG9rZW4=');

@$core.Deprecated('Use friendshipTicketDescriptor instead')
const FriendshipTicket$json = {
  '1': 'FriendshipTicket',
  '2': [
    {'1': 'issuer_user_id', '3': 1, '4': 1, '5': 9, '10': 'issuerUserId'},
    {'1': 'subject_user_id', '3': 2, '4': 1, '5': 9, '10': 'subjectUserId'},
    {'1': 'nonce', '3': 3, '4': 1, '5': 9, '10': 'nonce'},
    {'1': 'expires_at', '3': 4, '4': 1, '5': 3, '10': 'expiresAt'},
    {'1': 'signature', '3': 5, '4': 1, '5': 12, '10': 'signature'},
    {'1': 'enc_key_pub', '3': 6, '4': 1, '5': 12, '10': 'encKeyPub'},
  ],
};

/// Descriptor for `FriendshipTicket`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List friendshipTicketDescriptor = $convert.base64Decode(
    'ChBGcmllbmRzaGlwVGlja2V0EiQKDmlzc3Vlcl91c2VyX2lkGAEgASgJUgxpc3N1ZXJVc2VySW'
    'QSJgoPc3ViamVjdF91c2VyX2lkGAIgASgJUg1zdWJqZWN0VXNlcklkEhQKBW5vbmNlGAMgASgJ'
    'UgVub25jZRIdCgpleHBpcmVzX2F0GAQgASgDUglleHBpcmVzQXQSHAoJc2lnbmF0dXJlGAUgAS'
    'gMUglzaWduYXR1cmUSHgoLZW5jX2tleV9wdWIYBiABKAxSCWVuY0tleVB1Yg==');

@$core.Deprecated('Use friendshipRequestDescriptor instead')
const FriendshipRequest$json = {
  '1': 'FriendshipRequest',
  '2': [
    {
      '1': 'ticket',
      '3': 1,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.FriendshipTicket',
      '10': 'ticket'
    },
    {'1': 'encrypted_intro', '3': 2, '4': 1, '5': 12, '10': 'encryptedIntro'},
  ],
};

/// Descriptor for `FriendshipRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List friendshipRequestDescriptor = $convert.base64Decode(
    'ChFGcmllbmRzaGlwUmVxdWVzdBI7CgZ0aWNrZXQYASABKAsyIy5zaGlndWFuZy5yZWxheS52MS'
    '5GcmllbmRzaGlwVGlja2V0UgZ0aWNrZXQSJwoPZW5jcnlwdGVkX2ludHJvGAIgASgMUg5lbmNy'
    'eXB0ZWRJbnRybw==');

@$core.Deprecated('Use friendRequestDescriptor instead')
const FriendRequest$json = {
  '1': 'FriendRequest',
  '2': [
    {'1': 'to_user_id', '3': 1, '4': 1, '5': 9, '10': 'toUserId'},
    {'1': 'encrypted_intro', '3': 2, '4': 1, '5': 12, '10': 'encryptedIntro'},
  ],
};

/// Descriptor for `FriendRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List friendRequestDescriptor = $convert.base64Decode(
    'Cg1GcmllbmRSZXF1ZXN0EhwKCnRvX3VzZXJfaWQYASABKAlSCHRvVXNlcklkEicKD2VuY3J5cH'
    'RlZF9pbnRybxgCIAEoDFIOZW5jcnlwdGVkSW50cm8=');

@$core.Deprecated('Use friendshipResponseDescriptor instead')
const FriendshipResponse$json = {
  '1': 'FriendshipResponse',
  '2': [
    {'1': 'invite_id', '3': 1, '4': 1, '5': 9, '10': 'inviteId'},
    {'1': 'accept', '3': 2, '4': 1, '5': 8, '10': 'accept'},
  ],
};

/// Descriptor for `FriendshipResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List friendshipResponseDescriptor = $convert.base64Decode(
    'ChJGcmllbmRzaGlwUmVzcG9uc2USGwoJaW52aXRlX2lkGAEgASgJUghpbnZpdGVJZBIWCgZhY2'
    'NlcHQYAiABKAhSBmFjY2VwdA==');

@$core.Deprecated('Use friendshipViewDescriptor instead')
const FriendshipView$json = {
  '1': 'FriendshipView',
  '2': [
    {'1': 'user_id', '3': 1, '4': 1, '5': 9, '10': 'userId'},
    {'1': 'status', '3': 2, '4': 1, '5': 9, '10': 'status'},
    {'1': 'created_at', '3': 3, '4': 1, '5': 3, '10': 'createdAt'},
    {'1': 'encrypted_intro', '3': 4, '4': 1, '5': 12, '10': 'encryptedIntro'},
    {'1': 'username', '3': 5, '4': 1, '5': 9, '10': 'username'},
  ],
};

/// Descriptor for `FriendshipView`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List friendshipViewDescriptor = $convert.base64Decode(
    'Cg5GcmllbmRzaGlwVmlldxIXCgd1c2VyX2lkGAEgASgJUgZ1c2VySWQSFgoGc3RhdHVzGAIgAS'
    'gJUgZzdGF0dXMSHQoKY3JlYXRlZF9hdBgDIAEoA1IJY3JlYXRlZEF0EicKD2VuY3J5cHRlZF9p'
    'bnRybxgEIAEoDFIOZW5jcnlwdGVkSW50cm8SGgoIdXNlcm5hbWUYBSABKAlSCHVzZXJuYW1l');

@$core.Deprecated('Use keyBundleQueryDescriptor instead')
const KeyBundleQuery$json = {
  '1': 'KeyBundleQuery',
  '2': [
    {'1': 'user_id', '3': 1, '4': 1, '5': 9, '10': 'userId'},
  ],
};

/// Descriptor for `KeyBundleQuery`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List keyBundleQueryDescriptor = $convert
    .base64Decode('Cg5LZXlCdW5kbGVRdWVyeRIXCgd1c2VyX2lkGAEgASgJUgZ1c2VySWQ=');

@$core.Deprecated('Use keyBundleResponseDescriptor instead')
const KeyBundleResponse$json = {
  '1': 'KeyBundleResponse',
  '2': [
    {'1': 'identity_key_pub', '3': 1, '4': 1, '5': 12, '10': 'identityKeyPub'},
    {
      '1': 'signed_prekey_pub',
      '3': 2,
      '4': 1,
      '5': 12,
      '10': 'signedPrekeyPub'
    },
    {
      '1': 'signed_prekey_sig',
      '3': 3,
      '4': 1,
      '5': 12,
      '10': 'signedPrekeySig'
    },
    {
      '1': 'one_time_prekeys',
      '3': 4,
      '4': 3,
      '5': 11,
      '6': '.shiguang.relay.v1.PreKey',
      '10': 'oneTimePrekeys'
    },
  ],
};

/// Descriptor for `KeyBundleResponse`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List keyBundleResponseDescriptor = $convert.base64Decode(
    'ChFLZXlCdW5kbGVSZXNwb25zZRIoChBpZGVudGl0eV9rZXlfcHViGAEgASgMUg5pZGVudGl0eU'
    'tleVB1YhIqChFzaWduZWRfcHJla2V5X3B1YhgCIAEoDFIPc2lnbmVkUHJla2V5UHViEioKEXNp'
    'Z25lZF9wcmVrZXlfc2lnGAMgASgMUg9zaWduZWRQcmVrZXlTaWcSQwoQb25lX3RpbWVfcHJla2'
    'V5cxgEIAMoCzIZLnNoaWd1YW5nLnJlbGF5LnYxLlByZUtleVIOb25lVGltZVByZWtleXM=');

@$core.Deprecated('Use preKeyDescriptor instead')
const PreKey$json = {
  '1': 'PreKey',
  '2': [
    {'1': 'key_id', '3': 1, '4': 1, '5': 9, '10': 'keyId'},
    {'1': 'pub', '3': 2, '4': 1, '5': 12, '10': 'pub'},
  ],
};

/// Descriptor for `PreKey`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List preKeyDescriptor = $convert.base64Decode(
    'CgZQcmVLZXkSFQoGa2V5X2lkGAEgASgJUgVrZXlJZBIQCgNwdWIYAiABKAxSA3B1Yg==');

@$core.Deprecated('Use relayMessageDescriptor instead')
const RelayMessage$json = {
  '1': 'RelayMessage',
  '2': [
    {'1': 'msg_id', '3': 1, '4': 1, '5': 9, '10': 'msgId'},
    {'1': 'recipient_user_id', '3': 2, '4': 1, '5': 9, '10': 'recipientUserId'},
    {'1': 'sender_user_id', '3': 3, '4': 1, '5': 9, '10': 'senderUserId'},
    {'1': 'kind', '3': 4, '4': 1, '5': 9, '10': 'kind'},
    {'1': 'cipher', '3': 5, '4': 1, '5': 12, '10': 'cipher'},
    {'1': 'expires_at', '3': 6, '4': 1, '5': 3, '10': 'expiresAt'},
    {
      '1': 'header',
      '3': 7,
      '4': 3,
      '5': 11,
      '6': '.shiguang.relay.v1.RelayMessage.HeaderEntry',
      '10': 'header'
    },
  ],
  '3': [RelayMessage_HeaderEntry$json],
};

@$core.Deprecated('Use relayMessageDescriptor instead')
const RelayMessage_HeaderEntry$json = {
  '1': 'HeaderEntry',
  '2': [
    {'1': 'key', '3': 1, '4': 1, '5': 9, '10': 'key'},
    {'1': 'value', '3': 2, '4': 1, '5': 9, '10': 'value'},
  ],
  '7': {'7': true},
};

/// Descriptor for `RelayMessage`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List relayMessageDescriptor = $convert.base64Decode(
    'CgxSZWxheU1lc3NhZ2USFQoGbXNnX2lkGAEgASgJUgVtc2dJZBIqChFyZWNpcGllbnRfdXNlcl'
    '9pZBgCIAEoCVIPcmVjaXBpZW50VXNlcklkEiQKDnNlbmRlcl91c2VyX2lkGAMgASgJUgxzZW5k'
    'ZXJVc2VySWQSEgoEa2luZBgEIAEoCVIEa2luZBIWCgZjaXBoZXIYBSABKAxSBmNpcGhlchIdCg'
    'pleHBpcmVzX2F0GAYgASgDUglleHBpcmVzQXQSQwoGaGVhZGVyGAcgAygLMisuc2hpZ3Vhbmcu'
    'cmVsYXkudjEuUmVsYXlNZXNzYWdlLkhlYWRlckVudHJ5UgZoZWFkZXIaOQoLSGVhZGVyRW50cn'
    'kSEAoDa2V5GAEgASgJUgNrZXkSFAoFdmFsdWUYAiABKAlSBXZhbHVlOgI4AQ==');

@$core.Deprecated('Use relayAckRequestDescriptor instead')
const RelayAckRequest$json = {
  '1': 'RelayAckRequest',
  '2': [
    {'1': 'message_ids', '3': 1, '4': 3, '5': 9, '10': 'messageIds'},
  ],
};

/// Descriptor for `RelayAckRequest`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List relayAckRequestDescriptor = $convert.base64Decode(
    'Cg9SZWxheUFja1JlcXVlc3QSHwoLbWVzc2FnZV9pZHMYASADKAlSCm1lc3NhZ2VJZHM=');

@$core.Deprecated('Use clientFrameDescriptor instead')
const ClientFrame$json = {
  '1': 'ClientFrame',
  '2': [
    {'1': 'client_msg_id', '3': 1, '4': 1, '5': 9, '10': 'clientMsgId'},
    {'1': 'client_ts', '3': 2, '4': 1, '5': 3, '10': 'clientTs'},
    {
      '1': 'schedule',
      '3': 3,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.Envelope',
      '9': 0,
      '10': 'schedule'
    },
    {
      '1': 'relay',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.RelayMessage',
      '9': 0,
      '10': 'relay'
    },
    {
      '1': 'auth',
      '3': 5,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.AuthChallenge',
      '9': 0,
      '10': 'auth'
    },
    {
      '1': 'register',
      '3': 6,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.RegisterRequest',
      '9': 0,
      '10': 'register'
    },
    {
      '1': 'login',
      '3': 7,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.LoginRequest',
      '9': 0,
      '10': 'login'
    },
    {
      '1': 'friend_req',
      '3': 8,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.FriendshipRequest',
      '9': 0,
      '10': 'friendReq'
    },
    {
      '1': 'friend_resp',
      '3': 9,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.FriendshipResponse',
      '9': 0,
      '10': 'friendResp'
    },
  ],
  '8': [
    {'1': 'body'},
  ],
};

/// Descriptor for `ClientFrame`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List clientFrameDescriptor = $convert.base64Decode(
    'CgtDbGllbnRGcmFtZRIiCg1jbGllbnRfbXNnX2lkGAEgASgJUgtjbGllbnRNc2dJZBIbCgljbG'
    'llbnRfdHMYAiABKANSCGNsaWVudFRzEjkKCHNjaGVkdWxlGAMgASgLMhsuc2hpZ3VhbmcucmVs'
    'YXkudjEuRW52ZWxvcGVIAFIIc2NoZWR1bGUSNwoFcmVsYXkYBCABKAsyHy5zaGlndWFuZy5yZW'
    'xheS52MS5SZWxheU1lc3NhZ2VIAFIFcmVsYXkSNgoEYXV0aBgFIAEoCzIgLnNoaWd1YW5nLnJl'
    'bGF5LnYxLkF1dGhDaGFsbGVuZ2VIAFIEYXV0aBJACghyZWdpc3RlchgGIAEoCzIiLnNoaWd1YW'
    '5nLnJlbGF5LnYxLlJlZ2lzdGVyUmVxdWVzdEgAUghyZWdpc3RlchI3CgVsb2dpbhgHIAEoCzIf'
    'LnNoaWd1YW5nLnJlbGF5LnYxLkxvZ2luUmVxdWVzdEgAUgVsb2dpbhJFCgpmcmllbmRfcmVxGA'
    'ggASgLMiQuc2hpZ3VhbmcucmVsYXkudjEuRnJpZW5kc2hpcFJlcXVlc3RIAFIJZnJpZW5kUmVx'
    'EkgKC2ZyaWVuZF9yZXNwGAkgASgLMiUuc2hpZ3VhbmcucmVsYXkudjEuRnJpZW5kc2hpcFJlc3'
    'BvbnNlSABSCmZyaWVuZFJlc3BCBgoEYm9keQ==');

@$core.Deprecated('Use serverFrameDescriptor instead')
const ServerFrame$json = {
  '1': 'ServerFrame',
  '2': [
    {'1': 'seq_id', '3': 1, '4': 1, '5': 3, '10': 'seqId'},
    {'1': 'client_msg_id', '3': 2, '4': 1, '5': 9, '10': 'clientMsgId'},
    {
      '1': 'schedule',
      '3': 3,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.Envelope',
      '9': 0,
      '10': 'schedule'
    },
    {
      '1': 'relay',
      '3': 4,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.RelayMessage',
      '9': 0,
      '10': 'relay'
    },
    {
      '1': 'auth',
      '3': 5,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.AuthResponse',
      '9': 0,
      '10': 'auth'
    },
    {
      '1': 'friend',
      '3': 6,
      '4': 1,
      '5': 11,
      '6': '.shiguang.relay.v1.FriendshipView',
      '9': 0,
      '10': 'friend'
    },
    {'1': 'error', '3': 7, '4': 1, '5': 9, '9': 0, '10': 'error'},
  ],
  '8': [
    {'1': 'body'},
  ],
};

/// Descriptor for `ServerFrame`. Decode as a `google.protobuf.DescriptorProto`.
final $typed_data.Uint8List serverFrameDescriptor = $convert.base64Decode(
    'CgtTZXJ2ZXJGcmFtZRIVCgZzZXFfaWQYASABKANSBXNlcUlkEiIKDWNsaWVudF9tc2dfaWQYAi'
    'ABKAlSC2NsaWVudE1zZ0lkEjkKCHNjaGVkdWxlGAMgASgLMhsuc2hpZ3VhbmcucmVsYXkudjEu'
    'RW52ZWxvcGVIAFIIc2NoZWR1bGUSNwoFcmVsYXkYBCABKAsyHy5zaGlndWFuZy5yZWxheS52MS'
    '5SZWxheU1lc3NhZ2VIAFIFcmVsYXkSNQoEYXV0aBgFIAEoCzIfLnNoaWd1YW5nLnJlbGF5LnYx'
    'LkF1dGhSZXNwb25zZUgAUgRhdXRoEjsKBmZyaWVuZBgGIAEoCzIhLnNoaWd1YW5nLnJlbGF5Ln'
    'YxLkZyaWVuZHNoaXBWaWV3SABSBmZyaWVuZBIWCgVlcnJvchgHIAEoCUgAUgVlcnJvckIGCgRi'
    'b2R5');
