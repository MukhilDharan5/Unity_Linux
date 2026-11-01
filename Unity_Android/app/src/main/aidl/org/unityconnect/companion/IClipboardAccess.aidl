package org.unityconnect.companion;
import org.unityconnect.companion.IClipboardListener;
interface IClipboardAccess {
 String start(IClipboardListener listener) = 0;
 void refresh() = 1;
 void stop() = 2;
 void destroy() = 16777114;
}
