const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

exports.pushOnNewMessage = functions.firestore
  .document("messages/{messageId}")
  .onCreate(async (snap) => {
    const msg = snap.data();
    const to = msg.to;
    const text = String(msg.text || "رسالة جديدة").slice(0, 80);

    const userDoc = await admin.firestore().collection("users").doc(to).get();
    if (!userDoc.exists) return null;

    const token = userDoc.data().fcmToken;
    if (!token) return null;

    return admin.messaging().send({
      token,
      notification: {
        title: "رسالة جديدة",
        body: text
      },
      data: {
        from: String(msg.from || ""),
        to: String(msg.to || ""),
        messageId: snap.id
      }
    });
  });
