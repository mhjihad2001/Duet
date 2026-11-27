const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const USER1_UID = "husband_uid_1";
const USER2_UID = "wife_uid_2";
const INTRUDER_UID = "intruder_uid_3";
const ROOM_ID = "DUET-9921-AB";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated context is denied all reads and writes", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(USER1_UID).get());
  await assertFails(unauthDb.collection("rooms").where("memberUids", "array-contains", USER1_UID).get());
  await assertFails(
    unauthDb.collection("rooms").doc(ROOM_ID).set({
      creatorUid: USER1_UID,
      memberUids: [USER1_UID],
      inviteCodeHash: "abcdef1234567890abcdef1234567890",
      disappearingSeconds: 0,
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("User profile is strictly owner-only and rejects list scraping", async () => {
  const u1Db = testEnv.authenticatedContext(USER1_UID).firestore();
  const u2Db = testEnv.authenticatedContext(USER2_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    u1Db.collection("users").doc(USER1_UID).set({
      userId: USER1_UID,
      displayName: "Alex",
      fcmToken: "token_1",
      createdAt: now,
      updatedAt: now,
    })
  );

  // User 2 cannot read or list user profiles
  await assertFails(u2Db.collection("users").doc(USER1_UID).get());
  await assertFails(u2Db.collection("users").get());
  await assertFails(
    u2Db.collection("users").doc(USER1_UID).update({
      displayName: "Hijacked",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("2-seat couple room enforces max 2 members and blocks 3rd intruder", async () => {
  const u1Db = testEnv.authenticatedContext(USER1_UID).firestore();
  const u2Db = testEnv.authenticatedContext(USER2_UID).firestore();
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();
  const created = new Date(Date.now() - 5000);

  // User 1 creates room
  await assertSucceeds(
    u1Db.collection("rooms").doc(ROOM_ID).set({
      creatorUid: USER1_UID,
      memberUids: [USER1_UID],
      inviteCodeHash: "abcdef1234567890abcdef1234567890",
      disappearingSeconds: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // User 2 joins seat 2
  await assertSucceeds(
    u2Db.collection("rooms").doc(ROOM_ID).update({
      memberUids: [USER1_UID, USER2_UID],
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Intruder cannot read, list, or join as 3rd member
  await assertFails(intruderDb.collection("rooms").doc(ROOM_ID).get());
  await assertFails(intruderDb.collection("rooms").where("memberUids", "array-contains", USER1_UID).get());
  const intruderRoomSnap = await assertSucceeds(
    intruderDb.collection("rooms").where("memberUids", "array-contains", INTRUDER_UID).get()
  );
  assert.equal(intruderRoomSnap.size, 0);
  await assertFails(intruderDb.collection("rooms").get());
  await assertFails(
    intruderDb.collection("rooms").doc(ROOM_ID).update({
      memberUids: [USER1_UID, USER2_UID, INTRUDER_UID],
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Paired members can list their room and update disappearing timer
  await assertSucceeds(u1Db.collection("rooms").where("memberUids", "array-contains", USER1_UID).get());
  await assertSucceeds(
    u2Db.collection("rooms").doc(ROOM_ID).update({
      disappearingSeconds: 3600,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Encrypted messages: valid Kotlin payloads, status/reaction updates, and intruder denial", async () => {
  const u1Db = testEnv.authenticatedContext(USER1_UID).firestore();
  const u2Db = testEnv.authenticatedContext(USER2_UID).firestore();
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();
  const now = new Date(Date.now() - 3000);

  await assertSucceeds(
    u1Db.collection("rooms").doc(ROOM_ID).collection("messages").doc("msg_1").set({
      senderUid: USER1_UID,
      memberUids: [USER1_UID, USER2_UID],
      messageType: "TEXT",
      encryptedPayload: "base64IvPlusCiphertext==",
      status: "SENT",
      deletedForEveryone: false,
      expiresAtMillis: 0,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Partner queries with array-contains
  await assertSucceeds(
    u2Db.collection("rooms").doc(ROOM_ID).collection("messages")
      .where("memberUids", "array-contains", USER2_UID)
      .get()
  );

  // Unfiltered list query fails
  await assertFails(
    u2Db.collection("rooms").doc(ROOM_ID).collection("messages").get()
  );

  // Partner can mark READ and add reaction, but CANNOT tamper encryptedPayload
  await assertSucceeds(
    u2Db.collection("rooms").doc(ROOM_ID).collection("messages").doc("msg_1").update({
      status: "READ",
      reactionJson: "encReaction==",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    u2Db.collection("rooms").doc(ROOM_ID).collection("messages").doc("msg_1").update({
      encryptedPayload: "tampered==",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Intruder cannot read or write messages
  await assertFails(
    intruderDb.collection("rooms").doc(ROOM_ID).collection("messages").doc("msg_1").get()
  );
  await assertFails(
    intruderDb.collection("rooms").doc(ROOM_ID).collection("messages").doc("msg_2").set({
      senderUid: INTRUDER_UID,
      memberUids: [USER1_UID, USER2_UID, INTRUDER_UID],
      messageType: "TEXT",
      encryptedPayload: "evil==",
      status: "SENT",
      deletedForEveryone: false,
      expiresAtMillis: 0,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Presence and WebRTC signaling enforce member isolation", async () => {
  const u1Db = testEnv.authenticatedContext(USER1_UID).firestore();
  const u2Db = testEnv.authenticatedContext(USER2_UID).firestore();
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    u1Db.collection("rooms").doc(ROOM_ID).collection("presence").doc(USER1_UID).set({
      userId: USER1_UID,
      memberUids: [USER1_UID, USER2_UID],
      displayName: "Alex",
      online: true,
      typing: false,
      recordingAudio: false,
      fcmToken: "fcm_1",
      lastSeenAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    u2Db.collection("rooms").doc(ROOM_ID).collection("presence")
      .where("memberUids", "array-contains", USER2_UID)
      .get()
  );

  // User 2 cannot overwrite User 1's presence
  await assertFails(
    u2Db.collection("rooms").doc(ROOM_ID).collection("presence").doc(USER1_UID).update({
      online: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // WebRTC call creation and answer
  await assertSucceeds(
    u1Db.collection("rooms").doc(ROOM_ID).collection("calls").doc("call_1").set({
      callerUid: USER1_UID,
      memberUids: [USER1_UID, USER2_UID],
      callType: "VIDEO",
      status: "RINGING",
      encryptedOffer: "encSdpOffer==",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    u2Db.collection("rooms").doc(ROOM_ID).collection("calls").doc("call_1").update({
      status: "ACTIVE",
      encryptedAnswer: "encSdpAnswer==",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(
    intruderDb.collection("rooms").doc(ROOM_ID).collection("calls").doc("call_1").get()
  );
});
