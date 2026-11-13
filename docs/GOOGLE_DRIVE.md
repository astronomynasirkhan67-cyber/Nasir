> **Removed in 1.2. Planned again for 1.4 (LiveSync). Old code: tag V1.1.**

# Google Drive backup (optional)

IPVIO Stream can store its backup (history, continue watching, favorites, lists, settings and, if wanted, the
playlists) in the hidden app folder of the user's Google Drive. Sign-in works like on other TV apps: the TV shows a
code (and a QR code for the page), you confirm it on your phone at google.com/device. This also works on Fire TV, which has no Google services.

The app only uses the scope `https://www.googleapis.com/auth/drive.appdata`: it can see its own backup file and nothing
else in the Drive.

Without the two values below the Google Drive section stays hidden. The transfer over Wi-Fi
(Settings > Storage > Transfer over Wi-Fi) always works without any setup.

## Setup (once, free)

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project (e.g. "IPVIO Stream").
2. **APIs & Services > Library**: enable the **Google Drive API**.
3. **APIs & Services > OAuth consent screen** (Google Auth Platform):
   - User type **External**, app name "IPVIO Stream", your e-mail as support and developer contact.
   - Scopes: add `.../auth/drive.appdata`.
   - While the app is in "Testing", only the **test users** you add here can sign in (up to 100). Add your own Google account.
     To let everybody sign in, publish the app (Google may ask for a verification).
4. **APIs & Services > Credentials > Create credentials > OAuth client ID**
   - Application type: **TVs and Limited Input devices**
   - Copy the **Client ID** and **Client secret**.
5. In the project root, in `secrets.properties` (ignored by Git):

```properties
GOOGLE_CLIENT_ID=1234567890-abc.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-...
```

6. Build the app again. Settings > Storage now shows **Google Drive > Connect with Google**.

For this client type Google does not treat the client secret as a real secret, but keep it out of the repository anyway.
