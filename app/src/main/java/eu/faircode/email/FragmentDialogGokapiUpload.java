package eu.faircode.email;

/*
    This file is part of FairEmail Plus, a fork of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.
*/

import static android.app.Activity.RESULT_OK;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uploads a file to Gokapi, encrypted on the phone if the server uses end-to-end encryption, and hands its link to
 * the compose screen. Either an attachment of the draft, given in the arguments, or files you pick. You choose how
 * many downloads and days the link works for, starting from the settings. Closing it stops the upload.
 */
public class FragmentDialogGokapiUpload extends FragmentDialogBase {
    private static final int REQUEST_PICK = 1;

    // Set when the dialog goes, which stops the upload, and read by the upload on its own thread.
    private final AtomicBoolean stopped = new AtomicBoolean();
    private boolean running;

    private TextView tvFile;
    private Spinner spDownloads;
    private Spinner spDays;
    private ProgressBar pbUpload;
    private TextView tvStatus;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // An upload belongs to this dialog, so it isn't recreated, and stopped, by turning the phone.
        lockOrientation();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        Bundle args = requireArguments();
        boolean attachment = args.containsKey("attachment");

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ext_gokapi_upload, null);
        tvFile = view.findViewById(R.id.tvFile);
        spDownloads = view.findViewById(R.id.spDownloads);
        spDays = view.findViewById(R.id.spDays);
        TextView tvNote = view.findViewById(R.id.tvNote);
        pbUpload = view.findViewById(R.id.pbUpload);
        tvStatus = view.findViewById(R.id.tvStatus);

        ExtHooks.setDropdown(spDownloads, GokapiStore.downloadLabels());
        ExtHooks.setDropdown(spDays, GokapiStore.dayLabels());
        if (savedInstanceState == null) {
            spDownloads.setSelection(GokapiStore.DOWNLOADS.indexOf(GokapiStore.downloads(context)));
            spDays.setSelection(GokapiStore.DAYS.indexOf(GokapiStore.days(context)));
        }
        if (attachment) {
            long size = args.getLong("size", -1);
            tvFile.setText(args.getString("name") + (size < 0 ? "" : " (" + Helper.humanReadableByteCount(size) + ")"));
            tvFile.setVisibility(View.VISIBLE);
        }
        tvNote.setText(GokapiStore.e2eKey(context) != null
                ? "Encrypted on this phone before it's uploaded. Only the link, which goes in your email, can open it, and the server never sees the file or its name."
                : "Without an end-to-end key, the file goes to " + GokapiStore.server(context).replaceFirst("^https://", "") +
                " as it is, so the server can read it, if the server allows that.");

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(attachment ? "Share as a link" : "Share a file as a link")
                .setView(view)
                .setPositiveButton(attachment ? "Share" : "Choose file…", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        // Stays open while uploading, to show how far it is.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (running)
                return;
            if (attachment)
                upload(null);
            else
                pick();
        }));
        return dialog;
    }

    @Override
    public void onDestroy() {
        // Also when Android recreates the screen, as the upload's result would have nowhere to go.
        stopped.set(true);
        super.onDestroy();
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("*/*");
        // One file for a link of its own, as the link dialog takes one address.
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !requireArguments().containsKey(GokapiCompose.ARG_RESULT));
        try {
            startActivityForResult(Helper.getChooser(requireContext(), intent), REQUEST_PICK);
        } catch (Throwable ex) {
            Helper.reportNoViewer(requireContext(), intent, ex);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null)
            return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null)
            for (int i = 0; i < clip.getItemCount(); i++)
                if (clip.getItemAt(i).getUri() != null)
                    uris.add(clip.getItemAt(i).getUri());
        if (uris.isEmpty() && data.getData() != null)
            uris.add(data.getData());
        if (!uris.isEmpty())
            upload(uris);
    }

    /** Uploads the files picked, or else the attachment. */
    private void upload(ArrayList<Uri> uris) {
        Bundle args = new Bundle(requireArguments());
        if (uris != null)
            args.putParcelableArrayList("uris", uris);
        args.putInt("downloads", GokapiStore.DOWNLOADS.get(Math.max(0, spDownloads.getSelectedItemPosition())));
        args.putInt("days", GokapiStore.DAYS.get(Math.max(0, spDays.getSelectedItemPosition())));
        AtomicBoolean stopped = this.stopped;

        new SimpleTask<ArrayList<Bundle>>() {
            @Override
            protected void onPreExecute(Bundle args) {
                running = true;
                setEnabled(false);
                pbUpload.setProgress(0);
                pbUpload.setVisibility(View.VISIBLE);
                tvStatus.setText("Starting…");
            }

            @Override
            protected void onPostExecute(Bundle args) {
                running = false;
                setEnabled(true);
                pbUpload.setVisibility(View.GONE);
            }

            @Override
            protected ArrayList<Bundle> onExecute(Context context, Bundle args) throws Throwable {
                GokapiClient client = GokapiStore.client(context);
                byte[] e2eKey = GokapiStore.e2eKey(context);
                int downloads = args.getInt("downloads");
                int days = args.getInt("days");
                ArrayList<Bundle> links = new ArrayList<>();
                ArrayList<Uri> uris = args.getParcelableArrayList("uris");
                int count = uris == null ? 1 : uris.size();
                for (int i = 0; i < count; i++) {
                    Source source = null;
                    try {
                        source = uris == null
                                ? Source.attachment(context, args.getLong("attachment"))
                                : Source.uri(context, uris.get(i));
                        int index = i;
                        String label = (count > 1 ? (i + 1) + " of " + count + ": " : "") + source.name;
                        GokapiClient.Upload upload;
                        try (InputStream in = source.open(context)) {
                            upload = client.upload(in, source.size, source.name, source.type, e2eKey, downloads, days, new GokapiClient.Progress() {
                                private long reported = -1;

                                @Override
                                public void onProgress(long done, long total) {
                                    long permille = total <= 0 ? 1000 : done * 1000 / total;
                                    if (permille == reported)
                                        return;
                                    reported = permille;
                                    Bundle data = new Bundle();
                                    data.putInt("progress", (int) ((index * 1000 + permille) / count));
                                    postProgress(label + " · " + (permille / 10) + "%", data);
                                }

                                @Override
                                public boolean isStopped() {
                                    return stopped.get();
                                }
                            });
                        }
                        Bundle link = new Bundle();
                        link.putString("name", source.name);
                        link.putLong("size", source.size);
                        link.putString("url", upload.url);
                        link.putString("id", upload.id);
                        links.add(link);
                    } catch (IOException ex) {
                        // What was uploaded before still goes in the email, with what went wrong.
                        if (links.isEmpty() || (ex instanceof GokapiClient.GokapiException && ((GokapiClient.GokapiException) ex).stopped))
                            throw ex;
                        String name = source != null ? source.name : uris.get(i).getLastPathSegment();
                        args.putString(GokapiCompose.RESULT_ERROR, name + ": " + ex.getMessage());
                        break;
                    } finally {
                        if (source != null)
                            source.cleanup();
                    }
                }
                return links;
            }

            @Override
            protected void onProgress(CharSequence status, Bundle data) {
                pbUpload.setProgress(data.getInt("progress"));
                tvStatus.setText(status);
            }

            @Override
            protected void onExecuted(Bundle args, ArrayList<Bundle> links) {
                Bundle result = new Bundle();
                result.putParcelableArrayList(GokapiCompose.RESULT_LINKS, links);
                result.putInt(GokapiCompose.RESULT_DOWNLOADS, args.getInt("downloads"));
                result.putInt(GokapiCompose.RESULT_DAYS, args.getInt("days"));
                result.putLong(GokapiCompose.RESULT_ATTACHMENT, args.containsKey("uris") ? -1 : args.getLong("attachment", -1));
                result.putString(GokapiCompose.RESULT_ERROR, args.getString(GokapiCompose.RESULT_ERROR));
                getParentFragmentManager().setFragmentResult(args.getString(GokapiCompose.ARG_RESULT, ExtHooks.RESULT_COMPOSE), result);
                dismissAllowingStateLoss();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                if (ex instanceof GokapiClient.GokapiException && ((GokapiClient.GokapiException) ex).stopped)
                    tvStatus.setText("Stopped");
                else if (ex instanceof IOException || ex instanceof SecurityException)
                    tvStatus.setText(ex.getMessage());
                else {
                    Log.e(ex);
                    tvStatus.setText(ex.toString());
                }
            }
        }.setKeepAwake(true).execute(this, args, "ext:gokapi:upload");
    }

    private void setEnabled(boolean enabled) {
        spDownloads.setEnabled(enabled);
        spDays.setEnabled(enabled);
        Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog) {
            Button positive = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null)
                positive.setEnabled(enabled);
        }
    }

    /** A file to upload: its name, size and type, and how to read it. */
    private static class Source {
        String name;
        long size;
        String type;
        File file;
        Uri uri;
        boolean temporary;

        static Source attachment(Context context, long id) throws IOException {
            EntityAttachment attachment = DB.getInstance(context).attachment().getAttachment(id);
            if (attachment == null || !attachment.available)
                throw new FileNotFoundException("The attachment isn't in the draft anymore");
            Source source = new Source();
            source.file = attachment.getFile(context);
            source.name = TextUtils.isEmpty(attachment.name) ? source.file.getName() : attachment.name;
            source.size = source.file.length();
            source.type = attachment.getMimeType();
            return source;
        }

        /**
         * A file picked from another app. Its size has to be known before it's uploaded: if the app doesn't say, it's
         * copied into the cache first, and deleted again afterwards.
         */
        static Source uri(Context context, Uri uri) throws IOException {
            Source source = new Source();
            source.uri = uri;
            source.size = -1;
            ContentResolver resolver = context.getContentResolver();
            try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int size = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (name >= 0 && !cursor.isNull(name))
                        source.name = cursor.getString(name);
                    if (size >= 0 && !cursor.isNull(size))
                        source.size = cursor.getLong(size);
                }
            } catch (SecurityException ex) {
                throw new IOException("This file can't be read: " + ex.getMessage());
            }
            if (TextUtils.isEmpty(source.name))
                source.name = uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment();
            source.type = resolver.getType(uri);
            if (source.size < 0) {
                File dir = new File(context.getCacheDir(), "gokapi");
                dir.mkdirs();
                // A copy left behind by an upload that was cut off, such as by the app being closed, isn't kept.
                File[] left = dir.listFiles();
                if (left != null)
                    for (File file : left)
                        if (file.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
                            file.delete();
                source.file = File.createTempFile("upload", null, dir);
                source.temporary = true;
                try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(source.file)) {
                    if (in == null)
                        throw new FileNotFoundException(source.name);
                    Helper.copy(in, out);
                } catch (IOException | SecurityException ex) {
                    source.file.delete();
                    throw ex instanceof IOException ? (IOException) ex : new IOException("This file can't be read: " + ex.getMessage());
                }
                source.size = source.file.length();
            }
            return source;
        }

        InputStream open(Context context) throws IOException {
            if (file != null)
                return new FileInputStream(file);
            try {
                InputStream in = context.getContentResolver().openInputStream(uri);
                if (in == null)
                    throw new FileNotFoundException(name);
                return in;
            } catch (SecurityException ex) {
                throw new IOException("This file can't be read: " + ex.getMessage());
            }
        }

        void cleanup() {
            if (temporary)
                file.delete();
        }
    }
}
