# Pixel XL cloud preservation sample

The example file `pixel-xl-check.png` has been encrypted into `encoded/EPV1_....png` with **public demonstration-only password**:

`example-password-not-for-real-data`

1. Copy the sample encoded PNG to the **original Pixel XL**.
2. Turn on Google Photos backup for the device folder and wait for upload.
3. Download the backed-up file from Google Photos into a new directory.
4. Run from project root (replace `DOWNLOADED_DIR` with the actual folder):

   `py -m epv cloudcheck sample/encoded DOWNLOADED_DIR`

5. Test restore: `py -m epv restore DOWNLOADED_DIR -o sample/restored` and enter the public demo password.
6. Compare restored `pixel-xl-check.png` with the `sample/pixel-xl-check.png` original.

Do not use the public demonstration password for private photos or videos.
