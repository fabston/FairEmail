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

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sharing files as links with your Gokapi server, like the Betterbird add-on's Filelink: long-press an
 * attachment and it's swapped for a link, or choose ⋮ → Share a file as a link. The link goes where you're writing,
 * with the file's name, size, and how long it works. Undo takes it out again and deletes the file from the server.
 */
public class GokapiCompose {
    static final String SERVICE_NAME = "Gokapi";
    static final String RESULT_LINKS = "gokapi_links";
    static final String RESULT_ATTACHMENT = "gokapi_attachment";
    static final String RESULT_DOWNLOADS = "gokapi_downloads";
    static final String RESULT_DAYS = "gokapi_days";
    static final String RESULT_ERROR = "gokapi_error";
    // Where the upload dialog sends its result, if not to the compose screen.
    static final String ARG_RESULT = "result";
    private static final String RESULT_LINK = "ext:gokapi:link";
    private static final long DAY = 24 * 60 * 60 * 1000L;

    // Attachments swapped for a link, kept until Undo can no longer be tapped, so Undo can put them back.
    private static final Map<Long, EntityAttachment> swapped = new ConcurrentHashMap<>();

    /** Opens the dialog for sharing a file as a link: an attachment of this draft, or else one you pick. */
    static void show(Fragment compose, EntityAttachment attachment) {
        Context context = compose.requireContext();
        if (!GokapiStore.isSetUp(context)) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.ext_gokapi_title)
                    .setMessage("Add your Gokapi server and API key first, under " + GokapiClient.WHERE + ".")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        Bundle args = new Bundle();
        if (attachment != null) {
            args.putLong("attachment", attachment.id);
            args.putString("name", attachment.name);
            args.putLong("size", attachment.size == null ? -1 : attachment.size);
        }
        FragmentDialogGokapiUpload dialog = new FragmentDialogGokapiUpload();
        dialog.setArguments(args);
        dialog.show(compose.getParentFragmentManager(), "ext:gokapi:upload");
    }

    /**
     * In FairEmail's Insert link dialog, an Upload with Gokapi button next to its own Send upload: the file you pick
     * is uploaded, and its link and name fill in the dialog's address and text, as Send's upload does. Only once
     * Gokapi is set up. The dialog's content is wrapped rather than changed, so this hardly depends on its layout.
     */
    static void addToLinkDialog(Fragment dialog, View view, EditText etLink, EditText etTitle) {
        Context context = dialog.requireContext();
        if (!GokapiStore.isSetUp(context) || !(view instanceof ScrollView) || ((ScrollView) view).getChildCount() != 1)
            return;
        ScrollView scroll = (ScrollView) view;
        View content = scroll.getChildAt(0);
        scroll.removeView(content);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(content);

        Button upload = new Button(context, null, android.R.attr.buttonStyleSmall);
        upload.setText("Upload with Gokapi");
        upload.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.twotone_file_upload_24, 0);
        upload.setCompoundDrawablePadding(Helper.dp2pixels(context, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Helper.dp2pixels(context, 12);
        column.addView(upload, lp);
        scroll.addView(column);

        FragmentManager fm = dialog.getChildFragmentManager();
        upload.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString(ARG_RESULT, RESULT_LINK);
            FragmentDialogGokapiUpload uploader = new FragmentDialogGokapiUpload();
            uploader.setArguments(args);
            uploader.show(fm, "ext:gokapi:upload");
        });
        fm.setFragmentResultListener(RESULT_LINK, dialog, (key, result) -> {
            ArrayList<Bundle> links = result.getParcelableArrayList(RESULT_LINKS);
            if (links == null || links.isEmpty())
                return;
            etLink.setText(links.get(0).getString("url"));
            etTitle.setText(links.get(0).getString("name"));
        });
    }

    /** Whether an attachment can be swapped for a link: one in the draft, not shown in the text, and there in full. */
    static boolean canShare(EntityAttachment attachment) {
        return attachment != null && attachment.available && !attachment.isInline() && attachment.id != null;
    }

    /**
     * Puts the uploaded files' links where you're writing, then removes the attachment they were, if any. Called with
     * the upload dialog's result, once the compose screen has it.
     */
    static void insert(Fragment compose, EditText etBody, Bundle result) {
        View view = compose.getView();
        Editable edit = etBody.getText();
        ArrayList<Bundle> links = result.getParcelableArrayList(RESULT_LINKS);
        if (view == null || edit == null || links == null || links.isEmpty())
            return;
        int downloads = result.getInt(RESULT_DOWNLOADS);
        int days = result.getInt(RESULT_DAYS);
        long attachment = result.getLong(RESULT_ATTACHMENT, -1);
        String error = result.getString(RESULT_ERROR);

        int at = etBody.getSelectionEnd();
        if (at < 0 || at > edit.length())
            at = edit.length();
        SpannableStringBuilder block = new SpannableStringBuilder();
        if (at > 0 && edit.charAt(at - 1) != '\n')
            block.append('\n');
        block.append(linkText(links, downloads, days, new Date().getTime()));
        block.append('\n');
        edit.insert(at, block);
        int start = at;
        int end = at + block.length();
        etBody.setSelection(Math.min(end, edit.length()));
        String inserted = block.toString();

        Bundle args = new Bundle();
        args.putLong("attachment", attachment);
        ArrayList<String> ids = new ArrayList<>();
        for (Bundle link : links)
            ids.add(link.getString("id"));
        args.putStringArrayList("ids", ids);
        if (attachment >= 0)
            swapOut(compose, args);

        String shared = links.size() == 1 ? "Shared as a link with " + SERVICE_NAME : "Shared " + links.size() + " files as links";
        String text = error == null ? shared : shared + ", but: " + error;
        Helper.setSnackbarOptions(Snackbar.make(view, text, error == null ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_INDEFINITE))
                .setAction("Undo", v -> {
                    Editable now = etBody.getText();
                    boolean removed = now != null && end <= now.length() && now.subSequence(start, end).toString().equals(inserted);
                    if (removed)
                        now.delete(start, end);
                    undo(compose, view, args, removed);
                })
                .addCallback(new Snackbar.Callback() {
                    @Override
                    public void onDismissed(Snackbar snackbar, int event) {
                        if (event != DISMISS_EVENT_ACTION)
                            forget(view.getContext(), attachment);
                    }
                })
                .show();
    }

    /**
     * The text for the links: each file's name, linked, and its size, then how long the links work. In UTC,
     * with the month spelled out, so it reads the same wherever whoever gets it is, as the Betterbird add-on writes it.
     */
    static Spanned linkText(List<Bundle> links, int downloads, int days, long now) {
        SimpleDateFormat format = new SimpleDateFormat("d MMMM yyyy, HH:mm 'UTC'", Locale.ENGLISH);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (Bundle link : links) {
            int start = text.length();
            text.append(link.getString("name"));
            text.setSpan(new URLSpan(link.getString("url")), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            long size = link.getLong("size", -1);
            if (size >= 0)
                text.append(" (").append(Helper.humanReadableByteCount(size)).append(")");
            text.append('\n');
        }
        // What it's shared with isn't named: whoever gets the email only needs to know how long the link works.
        text.append("Download link, valid");
        if (downloads > 0)
            text.append(" for ").append(GokapiStore.downloadsText(downloads).toLowerCase(Locale.ROOT));
        text.append(" until ").append(format.format(new Date(now + days * DAY)));
        return text;
    }

    /**
     * Takes the attachment out of the draft, as its link is in the text now, keeping its file aside for Undo. Run
     * one after the other with Undo, so Undo always finds what this did.
     */
    private static void swapOut(Fragment compose, Bundle args) {
        new SimpleTask<Void>() {
            @Override
            protected Void onExecute(Context context, Bundle args) {
                long id = args.getLong("attachment");
                DB db = DB.getInstance(context);
                EntityAttachment attachment = db.attachment().getAttachment(id);
                if (attachment == null)
                    return null;
                File file = attachment.getFile(context);
                db.attachment().deleteAttachment(id);
                if (file.renameTo(keptFile(file)))
                    swapped.put(id, attachment);
                else
                    file.delete();
                return null;
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.unexpectedError(compose.getParentFragmentManager(), ex);
            }
        }.serial().execute(compose, args, "ext:gokapi:swap");
    }

    /** Puts the attachment back if it was one, then deletes the files from the server. */
    private static void undo(Fragment compose, View view, Bundle args, boolean removed) {
        new SimpleTask<Boolean>() {
            @Override
            protected Boolean onExecute(Context context, Bundle args) {
                long id = args.getLong("attachment");
                EntityAttachment attachment = id < 0 ? null : swapped.remove(id);
                if (attachment == null)
                    return false;
                File file = attachment.getFile(context);
                if (!keptFile(file).renameTo(file))
                    return false;
                try {
                    DB.getInstance(context).attachment().insertAttachment(attachment);
                    return true;
                } catch (Throwable ex) {
                    // The draft is gone, so there's nothing to attach it to.
                    Log.w(ex);
                    file.delete();
                    return false;
                }
            }

            @Override
            protected void onExecuted(Bundle args, Boolean restored) {
                deleteUploads(compose, view, args, Boolean.TRUE.equals(restored), removed);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.unexpectedError(compose.getParentFragmentManager(), ex);
            }
        }.serial().execute(view.getContext(), compose.getViewLifecycleOwner(), args, "ext:gokapi:restore");
    }

    private static void deleteUploads(Fragment compose, View view, Bundle args, boolean restored, boolean removed) {
        new SimpleTask<Void>() {
            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                GokapiClient client = GokapiStore.client(context);
                for (String upload : args.getStringArrayList("ids"))
                    client.delete(upload);
                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                String text = "Deleted from " + SERVICE_NAME;
                if (restored)
                    text += ", and attached again";
                if (!removed)
                    text += ". Your text changed since, so take the link out yourself.";
                message(view, text);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                message(view, "Couldn't delete it from " + SERVICE_NAME + ", so the link works until it expires: " + ex.getMessage());
            }
        }.execute(view.getContext(), compose.getViewLifecycleOwner(), args, "ext:gokapi:delete");
    }

    /** Lets go of an attachment kept for Undo, once Undo is gone. */
    private static void forget(Context context, long id) {
        EntityAttachment attachment = id < 0 ? null : swapped.remove(id);
        if (attachment != null)
            Helper.getSerialExecutor().submit(() -> keptFile(attachment.getFile(context)).delete());
    }

    /**
     * Where an attachment's file is kept for Undo: next to it, as attachments can be kept on external storage, which
     * a file can't simply be moved off.
     */
    private static File keptFile(File file) {
        return new File(file.getParentFile(), "gokapi-undo-" + file.getName());
    }

    private static void message(View view, String text) {
        Helper.setSnackbarOptions(Snackbar.make(view, text, Snackbar.LENGTH_LONG)).show();
    }
}
