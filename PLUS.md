# FairEmail Plus

A personal fork of [FairEmail](https://github.com/M66B/FairEmail) with extensions added. It installs next to FairEmail, as `eu.faircode.email.plus`, and is otherwise FairEmail as it is.

## Extensions

### addy.io

Set up under **Settings → Integrations → Extensions → addy.io**.

- **Check before sending**: runs with FairEmail's own checks when you tap Send. If an email would show your real address, FairEmail's send dialog opens with the reasons at the top, and **Send** sends anyway. The rules are the same as the Betterbird add-on's: someone not going through the alias, a reply that dropped the alias, your real address or name in the text, signature, quoted mail or subject, the wrong sender identity, signing or encryption, a Reply-To, the Free plan, and optionally any email that doesn't go through an alias at all.
- **Send from an alias**: in the compose screen, **⋮ → addy.io alias**. Pick or create an alias, and To is rewritten so addy.io sends from it, and the identity is switched to one addy.io accepts if needed.
- **Readable alias addresses**: mail sent or received through one of your aliases shows who it really reaches. In the message list, `mail+support=safetywing.com@…` becomes addy.io's @ icon and `support@safetywing.com`. In an opened message, it reads `support@safetywing.com via mail@…`. Only what's shown changes, so replies still go through the alias. When you send through an alias, the send dialog says who it's to and which alias it's from, too.
- **The side menu's addy.io section**, shown only once an API key is set, below your folders. It does what the Alfred workflow's keywords do:
  - **addy.io aliases** (`addy`): search, filter by active, inactive, pinned or deleted, and see your plan, counts and bandwidth. Tap an alias for its actions: copy, send from it, pin, turn off or on, edit the description, delete or restore, open on addy.io. Long-press copies it.
  - **New alias** (`addnew`): a name or a random one in your chosen format, a description and a domain. The address is copied, and **Send from it** starts an email from it.
  - **Send from an alias** (`addsend`): choose who it's to (suggested from FairEmail's contacts) and the alias, in either order. An alias you type that doesn't exist yet is offered too, for catch-all domains. It opens a new email with To and From already set.

### AI writing

Set up under **Settings → Integrations → Extensions → AI writing**, with your own OpenAI or Anthropic API key. Each service keeps its own key and model, so you can switch between them. Ported from the Betterbird add-on.

- In the compose screen, tap the wand in the top bar: **Fix spelling & grammar**, **Improve writing**, **Make shorter**, **More formal**, **More friendly**, **Translate** (the last language you chose is offered first), or **Say what to change** in your own words.
- It works on your selection, or else on everything you wrote. Quoted mail and your signature are kept apart from it by FairEmail, so they aren't sent, unless you've chosen **Edit plain text** or **Edit formatted text** on the quoted mail, which makes it part of your text.
- Your text is replaced straight away, and **Undo** in the message at the bottom puts it back. If you edit the text while the AI is working, your changes aren't overwritten.
- Your bold, italics, lists and links are kept. The AI can reword your text, but it can't add links, images, scripts or styling that weren't there.
- Only the text you rewrite is sent, only to the service you chose, and only when you choose an action. The key is kept apart from FairEmail's settings, like addy.io's.

This is separate from FairEmail's own AI feature (the robot icon), which writes new text from a prompt and adds it below your cursor.

### Gokapi

Shares large files as links instead of attachments, through your own [Gokapi](https://github.com/Forceu/Gokapi) server. Ported from the Betterbird add-on, with the same end-to-end encryption, so Gokapi's own download page opens the files. It's separate from FairEmail's own Send integration, which works with Firefox Send servers only.

Set up under **Settings → Integrations → Extensions → Gokapi**:

1. In Gokapi, open **API keys**, and create one that may upload, and nothing else. To have a file deleted when you tap **Undo**, let it delete files too.
2. Find your end-to-end key: the key Gokapi gave you when you set up end-to-end encryption. If you didn't keep it, open Gokapi's upload page in the browser you set it up in, open the browser's developer console, and run `localStorage.getItem("e2ekey")`.
3. Enter your server's address, which must be https, and both keys. Choose how many downloads (1 to 100, or unlimited) and days (1 to 30) a link works for, unless you choose otherwise for a file. **Save and test** checks both keys with the server, without uploading anything.

Then, in the compose screen:

- **Long-press an attachment → Share as a link instead.** It's uploaded, the link goes where you're writing, and the attachment is taken out of the email.
- **⋮ → Share a file as a link** does the same for files you pick, one or more, without attaching them first.
- **The link button → Upload with Gokapi**, next to FairEmail's own Send upload, works like it: the file you pick is uploaded, and its link and name fill in the dialog, to insert with OK. It's there wherever FairEmail's Insert link dialog is, in signatures and templates too. Cancel the dialog afterwards, and the file stays on the server until it expires, as with Send.

Each file is encrypted on the phone with a new key, uploaded in chunks, and the email gets its name, linked, its size, and how long the link works: `report.pdf (12 MB)`, then `Download link, valid for 10 downloads until 26 November 2026, 10:42 UTC`, without naming Gokapi. Several files get a line each, then `Download links, valid …`. Plain-text mail shows the address after the name. The file's name and key are only after the `#` in the link, which browsers never send to the server, so the server can't read either. It's also added to your list of encrypted files, so Gokapi's upload page shows it under its own name, as files you upload there do.

**Undo**, in the message at the bottom, takes the link out of the email, deletes the file from the server if your API key may delete files, and attaches the file again if it was an attachment. After that, a link you remove stays on the server until it runs out of downloads or expires, as the phone can't tell you removed it. Closing the dialog while it uploads stops the upload.

On a Gokapi server without end-to-end encryption, files are uploaded as they are, so the server can read them, and **Save and test** says so. That only happens while no end-to-end key is entered: with one, nothing is uploaded to a server that says it doesn't encrypt. The server must be https.

## How it fits into FairEmail

All extension code is in new files: `Addy*.java`, `Ai*.java`, `Gokapi*.java`, `ExtHooks.java`, `ExtHttp.java`, `PlusUpdates.java`, `FragmentAddyAliases.java`, `FragmentDialogAddy*.java`, `FragmentDialogAiSettings.java`, `FragmentDialogGokapi*.java`, `res/layout/*ext*`, `res/values/strings_ext.xml`, and the tests in `app/src/test`. FairEmail's own files only call into `ExtHooks`, from a line or two each, marked `Plus`:

| File | What the line does |
| --- | --- |
| `FragmentCompose.java` | Runs the extensions' checks with FairEmail's pre-send check, shows the send dialog if they found anything, adds and handles the compose menu entries (addy.io alias, Rewrite with AI, Share a file as a link), and listens for the alias and Gokapi dialogs' results |
| `AdapterAttachment.java` | Adds Share as a link instead to an attachment's long-press menu in the compose screen |
| `FragmentDialogInsertLink.java` | Adds Upload with Gokapi to the Insert link dialog |
| `FragmentDialogSend.java` | Shows what the checks found at the top of the send dialog |
| `FragmentOptionsIntegrations.java` | Adds the Extensions card to the Integrations settings |
| `ActivityView.java` | Adds the addy.io section to the side menu, and checks this fork's releases for updates instead of FairEmail's |
| `app/src/github/.../ActivityBilling.java` | Lets a Pro code bought for FairEmail activate here too, as it's made for FairEmail's app ID |
| `AdapterMessage.java` | Shows addresses through your aliases as who they really reach, in the message list and an opened message's header |
| `app/build.gradle` | The `.plus` app ID, JUnit and org.json for the tests, a quoted native build flag, so it builds in a folder with a space in its name, and which GitHub build an app is, for its update check |

To find them all: `git grep -n "Plus"` in those files.

## Updating from FairEmail

Only FairEmail's releases are followed, not every change on its master branch. GitHub does this by itself (below). By hand, with the release's tag, such as `1.2342`:

```
git fetch upstream tag 1.2342
git merge 1.2342     # conflicts can only be in the lines above
./gradlew testGithubDebugUnitTest --tests 'eu.faircode.email.Addy*' --tests 'eu.faircode.email.Ai*' --tests 'eu.faircode.email.PlusUpdates*' --tests 'eu.faircode.email.Gokapi*'
./gradlew assembleGithubRelease
```

### Automatically, on GitHub

`.github/workflows/plus-update.yml` checks every day whether FairEmail has published a new release. If it has, it merges it, runs the tests, builds the app, signs it with your key, pushes the merge to master, and publishes it as a release under **Releases**, with the APK attached. It does the same, without merging anything, whenever you push to master, and when you start it under **Actions → Plus update → Run workflow**. Only the five newest releases are kept.

If anything goes wrong, nothing is merged or published: the run fails, GitHub emails you, and the run's summary says what to do. When FairEmail changes a line next to one of the `Plus` lines, merge that release by hand, as above.

Releases of a public repository can be downloaded by anyone. They're your build of free software, signed with your key, and hold nothing private, but make the repository private if you'd rather they weren't public.

To set it up, once:

1. Push this repository to GitHub, and turn on Actions for it under the **Actions** tab (they're off in a fork).
2. Set up signing, below. Without it, nothing is merged or published.

The app built this way checks the repository's releases itself, as FairEmail's own check only works in apps FairEmail signed. When there's a newer one, it shows FairEmail's usual update notification: tap it for the release, or **Download** for the APK. It checks every few hours when you open the app, and long-pressing **About** in the side menu checks straight away. It can be turned off under **Settings → Integrations → Extensions → Check GitHub for updates**. Apps you build yourself don't check. To have updates installed without asking, use [Obtainium](https://github.com/ImranR98/Obtainium) instead, with this repository's address.

Three things it can't do:

- Push a merge in which FairEmail changed its own workflow files, as GitHub doesn't let a workflow do that. The run fails at the last step, and you merge that release by hand instead.
- Notice an extension that builds but no longer shows up. Check the addy.io warning, the Extensions card, the side menu and an attachment's long-press menu after each release.
- Keep running if the repository sees no activity for 60 days. GitHub then pauses the daily check until you turn it back on.

### Signing on GitHub

Your key is used by a separate job, `sign`, that runs none of the project's code: it downloads the built APK and signs it with Android's own tool. The key is kept in a GitHub environment that only this job can read, so nothing in the build can get at it. It's still a copy of your key on GitHub, so anyone who gets into your GitHub account could use it.

1. Under **Settings → Environments**, create an environment called `signing`, and limit it to the `master` branch. Don't add a required reviewer: it would stop every run until you approve it.
2. Add these as secrets of that environment, not of the repository:
   - `SIGNING_KEYSTORE_BASE64`: the output of `base64 -i your.keystore`
   - `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`
3. Under **Settings → Secrets and variables → Actions → Variables**, add `SIGN_APK` with the value `true`.

Each release's notes show its certificate fingerprint. It should match `keytool -list -v -keystore your.keystore`.

## Building

Debug builds are signed with Android's debug key. For a build you install for daily use, create your own key once, and point `keystore.properties` (never committed) at it:

```
storeFile=/path/to/your.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Then `./gradlew assembleGithubRelease`. Keep the key safe: updates must be signed with the same one.

## Known limits

- Gmail and Outlook sign-in (OAuth) is registered to FairEmail itself, so it doesn't work in a fork. Use an app password, or IMAP and SMTP with a password, for those accounts.
- The extensions' text is English only.
- The check before sending runs when you tap Send in the compose screen. A reply typed straight into a notification, and replies FairEmail sends by itself from a rule, aren't checked.
- The addy.io, AI writing and Gokapi settings, keys included, are in files of their own on the phone, not in FairEmail's settings. So they're not in FairEmail's log, debug info, exports or cloud sync, and you enter the keys again on a new phone.
- Gokapi can't delete a file when you remove its link by hand, or discard the email, as Betterbird does: only **Undo** does. Otherwise it stays until it runs out of downloads or expires. It also doesn't offer a link by itself when you attach a large file: long-press the attachment.
