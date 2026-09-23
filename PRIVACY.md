# StreamCut privacy policy

_Last updated: 23 September 2026_

StreamCut is a desktop app for cutting clips out of movies and series. It is a personal,
open-source project maintained by julianbou, not a company. This page explains what data the app
handles, where it goes, and how to get it deleted.

## Short version

- **You can use StreamCut without an account.** Everything then stays on your computer.
- **An account exists only to sync** your addons, library and progress between your own devices.
  That data is stored in a Supabase database that only your account can read.
- **Your clips never leave your computer.** StreamCut does not upload video, clips, or thumbnails.
- **No analytics, no ads, no tracking.** StreamCut does not sell or share your data.

## Data stored on your computer

StreamCut keeps its settings, library, watch progress and clip list in its data folder:

- macOS: `~/Library/Application Support/StreamCut`
- Windows: `%APPDATA%\StreamCut`
- Linux: `~/.config/streamcut`

Exported clips are saved to the folder you choose (by default, a `clips` folder in the data
folder). Uninstalling the app does not delete these folders; delete them yourself to remove
everything.

## Data stored in the cloud, only if you sign in

When you create an account and sign in, StreamCut syncs the following to its backend, a Supabase
project run by the maintainer:

| What | Why |
| --- | --- |
| Your email address and password (Supabase stores only a hash of the password) | To sign you in |
| Installed addons and plugins, including their URLs | To restore them on your other devices. Addon URLs can contain personal tokens from the addon provider. |
| Credentials you enter for connected services (for example debrid or metadata API keys) | So you do not have to enter them again on each device |
| Library, collections, watch progress and watched items | To sync them between devices |
| App settings and home layout | To sync them between devices |
| Device record: an installation ID, the app name and version, the platform and the device name | To list which devices are signed in to your account |

Each row is tied to your account, and the database only allows your account to read or change
it. Clips, clip files and thumbnails are not synced.

## Services StreamCut talks to

Using the app sends requests to third-party services. They receive your IP address and whatever
the request needs, and handle it under their own privacy policies:

- **The addons you install**, which look up and return streams and metadata.
- **Metadata services** such as TMDB, IMDb datasets and MDBList, to show titles, posters and
  ratings.
- **Services you connect yourself**, such as debrid providers.
- **Other peers**, when you play a torrent source: peer-to-peer streaming shares your IP address
  with the other peers of that torrent.
- **GitHub**, to check for new StreamCut versions and download them.
- **Supabase**, which hosts the backend, if you sign in.

StreamCut does not use crash reporting or analytics services.

## Deleting your data

- **Local data:** delete the data folder listed above, and your clips folder if you want.
- **Your account and everything synced to it:** open an issue at
  <https://github.com/julianbou/streamcut/issues> asking for account deletion, without posting
  your email address there. The maintainer will reply with a private way to confirm it is your
  account. Deleting the account removes every row tied to it.

## Changes

If this policy changes, the new version will be published at this address with a new date. The
full history is in the repository.
