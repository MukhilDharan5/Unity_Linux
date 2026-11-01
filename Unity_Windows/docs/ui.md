# Windows 11 appearance

The main window and phone-screen viewer use fully opaque, solid-color backgrounds. Window system backdrops are disabled, so the app does not show wallpaper or windows behind it. The title bar remains integrated into the window.

The shell has exactly two full-height background columns: a slightly lighter navigation pane on the left and a slightly darker content pane on the right. Both are opaque in Light and Dark appearances. Neither the title bar nor the content header introduces a separate background band. There is no sidebar divider or title-bar underline.

Unity's name is centered above navigation in the left column. Each page's heading is centered within the complete right pane. The caption-button inset is mirrored on the left, keeping the heading geometrically centered and clear of the buttons as the window or display scale changes. Descriptive text remains at the top of the page; the page title is not repeated below the title bar. The transparent title-bar drag region spans both panes, while Windows owns the caption buttons, resize behavior and Snap Layouts.

Buttons, text fields, combo boxes and navigation items use rounded rectangles with 8-DIP corners. Cards, dialogs, flyouts and the touchpad use 16-DIP corners with comfortable padding. Photo previews, album art and the phone-screen viewer have clipped, rounded rectangular media frames with 12-DIP corners. Cards and popups have solid fills without row-divider lines, and native WinUI controls retain their selection, focus, hover and disabled states.

System, Light and Dark appearances share this layout. Cached brushes change color in place when the theme changes, preserving text input, selection, scroll position and loaded content. High contrast uses Windows system colors. Appearance also follows through to the open phone viewer.

App styling does not change Windows transparency or accessibility preferences. System caption buttons, resizing, window rounding and Snap Layouts remain managed by Windows.

API reference: [custom title bars](https://learn.microsoft.com/windows/apps/develop/title-bar).
