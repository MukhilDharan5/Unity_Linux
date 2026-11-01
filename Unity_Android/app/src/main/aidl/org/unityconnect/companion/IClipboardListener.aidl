package org.unityconnect.companion;
oneway interface IClipboardListener {
 void onText(String text, long observedAtNanos);
 void onError(String reason);
}
