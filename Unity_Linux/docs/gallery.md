# Photos, videos and file clipboard

The latest Linux update lets you drag a photo or video from its gallery tile into a file manager or another app. The drag downloads the original over the existing authenticated file-transfer route and supplies `text/uri-list` only after size/SHA-256 verification and a matching operation result. The destination receives the original file, not its gallery thumbnail. Transfers are copies; the source stays on the phone. A permanent verified download remains available locally after the drag, with the original filename and a collision suffix when needed. The 64 MiB file limit and photo/video/file permissions apply.

Dragging starts the download asynchronously and keeps the GTK interface responsive. Connection, device or permission changes fail the pending drag rather than handing out another item's path. Unsupported destination apps may refuse file drops. Dropping local files onto Unity continues to select them in Files for sending to the phone.

Install companion **0.1.20** with the matching Linux update. On Android enable file transfers and Photos and videos, then open **Photo and video access** to grant video access or update the selected items. Existing photo-only permission continues to show the accessible photos.

The desktop **Photos** tab lists up to 60 recent accessible photos and videos together, newest first. Both types load bounded JPEG thumbnails; video tiles show a play icon and duration. Opening a tile shows its thumbnail and original-file actions. Right-click a tile, or focus it and press Menu/Shift+F10, for **Copy to clipboard** and **Download original**.

Android grants photo and video access separately. After moving from a photo-only installation, open **Features → Photo and video access** on the phone and include videos, then refresh the Linux gallery. Repeated thumbnail requests reuse bounded operation-history capacity after their results have been read; completed previews no longer block gallery refreshes for ten minutes.

**Download original** keeps the verified original in Unity's received-file storage and posts the usual received-file alert. The existing 64 MiB transfer limit applies to videos as well as photos.

**Copy to clipboard** downloads a separate original into private clipboard storage. It puts the original file, with its original name, on the file clipboard, suitable for pasting into a file manager. This is a file copy, not a low-resolution thumbnail or a text path. The source file on the phone is unchanged. A foreground clipboard owner serves repeated pastes and removes only its private downloaded copy when ownership is replaced or cleared. Closing the desktop window keeps the daemon's clipboard owner alive. Ordinary downloads and pasted destination files are retained.

Clipboard downloads are correlated with the requesting operation and its exact authenticated transport. Their verified bytes never enter the permanent received-file cache or create a received-file notification. Rejected ownership, invalid transfer IDs, aborted transfers and link loss clean up unfinished temporary downloads. Missing responses have a bounded pending-file retention period; they never become clipboard contents.

Wayland uses `wl-clipboard`; X11 uses `xclip`. The file clipboard uses `text/uri-list`, which GTK can deserialize as a file list; current [Nautilus paste handling](https://github.com/GNOME/nautilus/blob/main/src/nautilus-files-view.c) supports that type.

`tests/gallery_integration.py` uses real TLS/D-Bus traffic and a clipboard-owner fixture to check exact original bytes, repeated pastes, replacement/clear cleanup, preserved permanent downloads, mismatched transfer IDs, backend failure and feature revocation. `tests/gallery_ui.py` exercises the real GTK video tile, duration badge, thumbnail and context actions. Android `GalleryTest` checks separate photo/video permissions, ordering and original selection tokens; only the unsupported Robolectric provider-thumbnail decoder is substituted, while the production URI routing and JPEG encoding run.
