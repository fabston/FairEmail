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
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rewriting what you write with AI, like the Betterbird add-on: fix, improve, shorten, change the tone, translate,
 * or say what to change. It works on your selection, or else on everything you wrote: FairEmail keeps quoted mail and
 * your signature apart from it, so they aren't sent, unless you've chosen to edit the quoted mail as part of your text.
 * Your text is replaced straight away, and Undo puts it back.
 */
public class AiCompose {
    static void show(Fragment compose, EditText etBody) {
        Context context = compose.requireContext();
        if (!AiStore.isSetUp(context)) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.ext_ai_title)
                    .setMessage("Add your OpenAI or Anthropic API key first, under Settings → Integrations → Extensions → AI writing.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        // One at a time, or two changes could be made to the same text.
        if (etBody.getTag(R.id.etBody) != null) {
            ToastEx.makeText(context, "Still working on the last change", Toast.LENGTH_SHORT).show();
            return;
        }

        int from = Math.min(etBody.getSelectionStart(), etBody.getSelectionEnd());
        int to = Math.max(etBody.getSelectionStart(), etBody.getSelectionEnd());
        boolean selection = from >= 0 && from < to;
        int start = selection ? from : 0;
        int end = selection ? to : etBody.length();
        Spanned original = new SpannableStringBuilder(etBody.getText(), start, end);
        if (original.toString().trim().isEmpty()) {
            ToastEx.makeText(context, "There's no text of yours to work on yet", Toast.LENGTH_SHORT).show();
            return;
        }

        String translateTo = AiStore.translateTo(context);
        List<String> labels = new ArrayList<>();
        for (AiPrompts.Action action : AiPrompts.QUICK_ACTIONS)
            labels.add(action.label);
        labels.add("Translate to " + translateTo);
        labels.add("Translate to another language…");
        labels.add("Say what to change…");
        int quick = AiPrompts.QUICK_ACTIONS.size();

        new AlertDialog.Builder(context)
                .setTitle(selection ? "Rewrite your selection" : "Rewrite your email")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < quick)
                        run(compose, etBody, start, end, original, AiPrompts.QUICK_ACTIONS.get(which), null, null);
                    else if (which == quick)
                        run(compose, etBody, start, end, original, AiPrompts.Action.TRANSLATE, translateTo, null);
                    else if (which == quick + 1)
                        chooseLanguage(compose, etBody, start, end, original);
                    else
                        askInstruction(compose, etBody, start, end, original);
                })
                .show();
    }

    private static void chooseLanguage(Fragment compose, EditText etBody, int start, int end, Spanned original) {
        new AlertDialog.Builder(compose.requireContext())
                .setTitle("Translate to")
                .setItems(AiPrompts.LANGUAGES.toArray(new String[0]), (dialog, which) -> {
                    String language = AiPrompts.LANGUAGES.get(which);
                    AiStore.setTranslateTo(compose.requireContext(), language);
                    run(compose, etBody, start, end, original, AiPrompts.Action.TRANSLATE, language, null);
                })
                .show();
    }

    private static void askInstruction(Fragment compose, EditText etBody, int start, int end, Spanned original) {
        Context context = compose.requireContext();
        EditText input = new EditText(context);
        input.setHint("Like: make it sound more confident");
        int padding = Helper.dp2pixels(context, 24);
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(input);
        new AlertDialog.Builder(context)
                .setTitle("What should change?")
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        run(compose, etBody, start, end, original, AiPrompts.Action.CUSTOM, null, input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void run(Fragment compose, EditText etBody, int start, int end, Spanned original,
                            AiPrompts.Action action, String language, String instruction) {
        Context context = compose.requireContext();
        View view = compose.getView();
        if (view == null)
            return;
        String originalText = original.toString();
        // Sent as HTML, so your bold, italics, lists and links are kept.
        String html = Jsoup.parse(HtmlHelper.toHtml(original, context)).body().html();
        Bundle args = new Bundle();
        args.putString("html", html);
        args.putString("action", action.name());
        args.putString("language", language);
        args.putString("instruction", instruction);

        etBody.setTag(R.id.etBody, true);
        Snackbar progress = Helper.setSnackbarOptions(Snackbar.make(view,
                "Rewriting with " + AiClient.serviceName(AiStore.provider(context)) + "…", Snackbar.LENGTH_INDEFINITE));
        progress.show();

        new SimpleTask<CharSequence>() {
            @Override
            protected CharSequence onExecute(Context context, Bundle args) throws Throwable {
                String html = args.getString("html");
                AiPrompts.Request request = AiPrompts.editRequest(AiPrompts.Action.valueOf(args.getString("action")),
                        args.getString("language"), args.getString("instruction"), Collections.singletonList(html));
                String answer = AiStore.client(context).edit(request, 1).get(0);
                CharSequence result = fit(HtmlHelper.fromHtml(AiPrompts.cleanHtml(answer, html), context), originalText);
                if (result.toString().trim().isEmpty())
                    throw new AiClient.AiException("The AI gave back no text, so nothing was replaced. Try again.");
                return result;
            }

            @Override
            protected void onExecuted(Bundle args, CharSequence result) {
                Editable edit = etBody.getText();
                // If you've edited the text while the AI was working, your changes aren't overwritten.
                if (edit == null || end > edit.length() || !edit.subSequence(start, end).toString().equals(originalText)) {
                    message(view, "Your text changed while the AI was working, so nothing was replaced. Try again.");
                    return;
                }
                CharSequence before = new SpannableStringBuilder(edit, start, end);
                edit.replace(start, end, result);
                int changedEnd = start + result.length();
                etBody.setSelection(Math.min(changedEnd, edit.length()));
                String resultText = result.toString();
                Helper.setSnackbarOptions(Snackbar.make(view, "Rewritten with AI", Snackbar.LENGTH_LONG))
                        .setAction("Undo", v -> {
                            Editable now = etBody.getText();
                            if (now != null && changedEnd <= now.length() &&
                                    now.subSequence(start, changedEnd).toString().equals(resultText))
                                now.replace(start, changedEnd, before);
                            else
                                message(view, "Your text has changed since, so it can't be put back.");
                        })
                        .show();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                if (ex instanceof IOException || ex instanceof IllegalArgumentException)
                    message(view, ex.getMessage());
                else {
                    // Only what kind of error, as its message could quote your text.
                    Log.e("AI rewrite failed: " + ex.getClass().getName());
                    message(view, "The AI couldn't rewrite this: " + ex);
                }
            }

            @Override
            protected void onPostExecute(Bundle args) {
                etBody.setTag(R.id.etBody, null);
                progress.dismiss();
            }
        }.execute(compose, args, "ext:ai:rewrite");
    }

    private static void message(View view, String text) {
        Helper.setSnackbarOptions(Snackbar.make(view, text, Snackbar.LENGTH_LONG)).show();
    }

    /**
     * The rewritten text, with as many line breaks at its ends as yours had: converting from HTML adds some after the
     * last paragraph, which would otherwise pile up each time.
     */
    private static CharSequence fit(Spanned result, String original) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(result);
        int keep = 0;
        while (keep < original.length() && original.charAt(original.length() - 1 - keep) == '\n')
            keep++;
        while (ssb.length() > 0 && Character.isWhitespace(ssb.charAt(ssb.length() - 1)) &&
                (ssb.charAt(ssb.length() - 1) != '\n' || trailingNewlines(ssb) > keep))
            ssb.delete(ssb.length() - 1, ssb.length());
        if (!original.startsWith("\n"))
            while (ssb.length() > 0 && ssb.charAt(0) == '\n')
                ssb.delete(0, 1);
        return ssb;
    }

    private static int trailingNewlines(CharSequence text) {
        int count = 0;
        while (count < text.length() && text.charAt(text.length() - 1 - count) == '\n')
            count++;
        return count;
    }
}
