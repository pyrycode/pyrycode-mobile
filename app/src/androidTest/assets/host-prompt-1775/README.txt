Host system prompt editor #1775
Captured 2026-10-04 UTC on managed pixel2Api33Atd, API 33.
412x892, density/font scale 1, static dark, software dialog decor-view draw.
Five synthetic fixture states: host-empty, host-filled, editor-empty, editor-filled, editor-default.
Default fixture text is synthetic; production defaults are always read from the daemon.
Each focused dialog has asserted close/OK pixels and nonblank content.
api33-green.xml records 2 executed/passed, 0 failures/errors, 0 skipped:
HostPromptCaptureTest.darkHostAndPromptStates and MobileModalTest.editHostFieldAndUnpairRemainReachableWithKeyboard.
Compared with ticket Figma nodes 776:10333, 776:10387, 777:10461, 777:10515, 780:7063.
These decor captures establish modal geometry/content; they do not establish hardware blur or real system bars.
The full curated live result is pending dispatcher execution; these are not live acceptance artifacts.
