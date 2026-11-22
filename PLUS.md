# FairEmail Plus

A personal fork of [FairEmail](https://github.com/M66B/FairEmail) with extensions added. It installs next to FairEmail, as `eu.faircode.email.plus`, and is otherwise FairEmail as it is.

## Extensions

### addy.io

Set up under **Settings → Integrations → Extensions → addy.io**.

- **Check before sending**: runs with FairEmail's own checks when you tap Send. If an email would show your real address, FairEmail's send dialog opens with the reasons at the top, and **Send** sends anyway. The rules are the same as the Betterbird add-on's: someone not going through the alias, a reply that dropped the alias, your real address or name in the text, signature, quoted mail or subject, the wrong sender identity, signing or encryption, a Reply-To, the Free plan, and optionally any email that doesn't go through an alias at all.
- **Send from an alias**: in the compose screen, **⋮ → addy.io alias**. Pick or create an alias, and To is rewritten so addy.io sends from it, and the identity is switched to one addy.io accepts if needed.
- **The side menu's addy.io section**, shown only once an API key is set, below your folders. It does what the Alfred workflow's keywords do:
  - **addy.io aliases** (`addy`): search, filter by active, inactive, pinned or deleted, and see your plan, counts and bandwidth. Tap an alias for its actions: copy, send from it, pin, turn off or on, edit the description, delete or restore, open on addy.io. Long-press copies it.
  - **New alias** (`addnew`): a name or a random one in your chosen format, a description and a domain. The address is copied, and **Send from it** starts an email from it.
  - **Send from an alias** (`addsend`): choose who it's to (suggested from FairEmail's contacts) and the alias, in either order. An alias you type that doesn't exist yet is offered too, for catch-all domains. It opens a new email with To and From already set.

## How it fits into FairEmail

All extension code is in new files: `Addy*.java`, `ExtHooks.java`, `FragmentAddyAliases.java`, `FragmentDialogAddy*.java`, `res/layout/*ext*`, `res/values/strings_ext.xml`, and the tests in `app/src/test`. FairEmail's own files only call into `ExtHooks`, from a line or two each, marked `Plus`:

| File | What the line does |
| --- | --- |
| `FragmentCompose.java` | Runs the extensions' checks with FairEmail's pre-send check, shows the send dialog if they found anything, adds and handles the compose menu entry, and listens for the alias dialog's result |
| `FragmentDialogSend.java` | Shows what the checks found at the top of the send dialog |
| `FragmentOptionsIntegrations.java` | Adds the Extensions card to the Integrations settings |
| `ActivityView.java` | Adds the addy.io section to the side menu |
| `app/build.gradle` | The `.plus` app ID, JUnit for the tests, and a quoted native build flag, so it builds in a folder with a space in its name |

To find them all: `git grep -n "Plus"` in those files.

## Updating from FairEmail

```
git fetch upstream
git merge upstream/master     # conflicts can only be in the lines above
./gradlew testGithubDebugUnitTest --tests 'eu.faircode.email.Addy*'
./gradlew assembleGithubRelease
```

### Automatically, on GitHub

`.github/workflows/plus-update.yml` does the above every Monday, and whenever you start it under **Actions → Plus update → Run workflow**. If FairEmail has something new, it merges it into the branch `plus/upstream-update`, runs the tests, builds the app and opens a pull request. If the merge conflicts or the build breaks, the run fails, GitHub emails you, and the run's summary says what to fix by hand.

To set it up, once:

1. Push this repository to GitHub, and turn on Actions for it under the **Actions** tab (they're off in a fork).
2. Under **Settings → Actions → General**, tick **Allow GitHub Actions to create and approve pull requests**.

Three things it can't do:

- Push a merge in which FairEmail changed its own workflow files, as GitHub doesn't let a workflow do that. The run fails at the last step, and you merge on your own computer instead.
- Notice an extension that builds but no longer shows up. The pull request lists what to check by hand.
- Keep running if the repository sees no activity for 60 days. GitHub then pauses the schedule until you turn it back on.

### A signed APK from GitHub (optional)

Without this, the workflow only checks the merge, and you build the app you install yourself, as under Building. With it, each run also leaves a signed APK under the run's **Artifacts**.

Your key is used by a separate job, `sign`, that runs none of the project's code: it downloads the built APK and signs it with Android's own tool. The key is kept in a GitHub environment that only this job can read, so nothing in the build can get at it. It's still a copy of your key on GitHub, so anyone who gets into your GitHub account could use it.

1. Under **Settings → Environments**, create an environment called `signing`. Limit it to the `master` branch, and add yourself as a required reviewer if you want to approve each signing.
2. Add these as secrets of that environment, not of the repository:
   - `SIGNING_KEYSTORE_BASE64`: the output of `base64 -i your.keystore`
   - `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`
3. Under **Settings → Secrets and variables → Actions → Variables**, add `SIGN_APK` with the value `true`.

The run's summary shows the signed APK's certificate fingerprint. It should match `keytool -list -v -keystore your.keystore`.

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
