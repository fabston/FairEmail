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
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI's and Anthropic's APIs, called directly with your own key, with no server in between. Ported from the
 * Betterbird add-on. Requests only ever go to the service's own address.
 */
public class AiClient {
    static final String OPENAI = "openai";
    static final String ANTHROPIC = "anthropic";
    private static final String OPENAI_URL = "https://api.openai.com/v1/";
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/";
    private static final String ANTHROPIC_VERSION = "2026-10-21";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final int MAX_TOKENS = 16000;
    private static final int TIMEOUT = 120; // seconds, as a long email takes a while

    public static class AiException extends IOException {
        AiException(String message) {
            super(message);
        }
    }

    public static class Model {
        final String id;
        final String name;
        final String note;

        Model(String id, String name, String note) {
            this.id = id;
            this.name = name;
            this.note = note;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Model> OPENAI_MODELS = Arrays.asList(
            new Model("gpt-6.2-sol", "GPT-6.2 Sol", "Very good writing, at about a cent for a typical email."),
            new Model("gpt-6.2-astra", "GPT-6.2 Astra", "OpenAI's best, at about four times the price of Sol."),
            new Model("gpt-6-luna", "GPT-6 Luna", "The fastest, at a fraction of a cent an email. Good for spelling and grammar."));

    static final List<Model> CLAUDE_MODELS = Arrays.asList(
            new Model("claude-opus-5-5", "Claude Opus 5.5", "The best writing. About 2 to 4 cents for a typical email."),
            new Model("claude-sonnet-5-5", "Claude Sonnet 5.5", "Nearly as good, at about half the price."),
            new Model("claude-haiku-5-5", "Claude Haiku 5.5", "The fastest, at a fraction of a cent an email. Good for grammar and translation."));

    static List<Model> models(String provider) {
        return ANTHROPIC.equals(provider) ? CLAUDE_MODELS : OPENAI_MODELS;
    }

    static String serviceName(String provider) {
        return ANTHROPIC.equals(provider) ? "Anthropic" : "OpenAI";
    }

    private final Context context;
    private final String provider;
    private final String key;
    private final String model;

    AiClient(Context context, String provider, String key, String model) {
        this.context = context;
        this.provider = provider;
        this.key = key;
        this.model = model;
    }

    /** Checks the key, and that it can use the model. Costs nothing, as no text is sent. */
    void checkModel() throws IOException {
        request("GET", "models/" + Uri.encode(model), null);
    }

    /** Your text rewritten as the request asks: one HTML segment back for each of the count that were sent. */
    List<String> edit(AiPrompts.Request request, int count) throws IOException {
        JSONObject answer = ANTHROPIC.equals(provider) ? askClaude(request) : askOpenAI(request);
        JSONArray segments = answer.optJSONArray("segments");
        if (segments == null || segments.length() != count)
            throw new AiException("The answer didn't match your text. Try again.");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < segments.length(); i++) {
            Object segment = segments.opt(i);
            if (!(segment instanceof String))
                throw new AiException("The answer didn't match your text. Try again.");
            result.add((String) segment);
        }
        return result;
    }

    private JSONObject askOpenAI(AiPrompts.Request request) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    // The rules go first, as a developer message: from GPT-6.2 on, top-level instructions would come after the email.
                    .put("input", new JSONArray()
                            .put(new JSONObject().put("role", "developer").put("content", request.system))
                            .put(new JSONObject().put("role", "user").put("content", request.prompt)))
                    // Not kept by OpenAI as a stored conversation. It still keeps requests for up to 30 days to check for abuse.
                    .put("store", false)
                    .put("max_output_tokens", MAX_TOKENS)
                    // Editing and translating need little reasoning, and low effort keeps them quick and cheap.
                    .put("reasoning", new JSONObject().put("effort", "low"))
                    .put("text", new JSONObject().put("format", new JSONObject()
                            .put("type", "json_schema").put("name", "answer")
                            .put("schema", new JSONObject(AiPrompts.SEGMENTS_SCHEMA)).put("strict", true)));
            JSONObject response = request("POST", "responses", body);

            String status = response.optString("status");
            if ("incomplete".equals(status)) {
                JSONObject details = response.optJSONObject("incomplete_details");
                String reason = details == null ? "" : details.optString("reason");
                if ("max_output_tokens".equals(reason))
                    throw new AiException("The text is too long to do in one go. Select a part of it and try again.");
                if ("content_filter".equals(reason))
                    throw new AiException("OpenAI declined to do this. Try rewording your request.");
                throw new AiException("OpenAI stopped before finishing" + (reason.isEmpty() ? "" : " (" + reason + ")") + ". Try again.");
            }
            if (!status.isEmpty() && !"completed".equals(status))
                throw new AiException("OpenAI couldn't finish (" + status + "). Try again.");

            StringBuilder text = new StringBuilder();
            JSONArray output = response.optJSONArray("output");
            for (int i = 0; output != null && i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                JSONArray content = item == null || !"message".equals(item.optString("type")) ? null : item.optJSONArray("content");
                for (int j = 0; content != null && j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    String type = part == null ? "" : part.optString("type");
                    if ("refusal".equals(type))
                        throw new AiException("OpenAI declined to do this. Try rewording your request.");
                    if ("output_text".equals(type))
                        text.append(part.optString("text"));
                }
            }
            return new JSONObject(text.toString());
        } catch (JSONException ex) {
            throw new AiException("OpenAI's answer couldn't be read. Try again.");
        }
    }

    private JSONObject askClaude(AiPrompts.Request request) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", MAX_TOKENS)
                    .put("system", request.system)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", request.prompt)))
                    // Editing and translating need little reasoning, and low effort keeps them quick and cheap.
                    .put("output_config", new JSONObject().put("effort", "low").put("format", new JSONObject()
                            .put("type", "json_schema").put("schema", new JSONObject(AiPrompts.SEGMENTS_SCHEMA))));
            // If Claude turns a request down, Anthropic can retry it on another model in the same request. Haiku has no such fallback.
            if (!model.startsWith("claude-haiku"))
                body.put("fallbacks", "default");
            JSONObject message = request("POST", "messages", body);

            String stop = message.optString("stop_reason");
            if ("refusal".equals(stop)) {
                JSONObject details = message.optJSONObject("stop_details");
                String category = details == null ? "" : details.optString("category");
                throw new AiException("Claude declined to do this" + (category.isEmpty() ? "" : " (" + category + ")") + ". Try rewording your request.");
            }
            if ("max_tokens".equals(stop))
                throw new AiException("The text is too long to do in one go. Select a part of it and try again.");

            // Thinking and fallback blocks are skipped, as only text blocks carry the answer.
            StringBuilder text = new StringBuilder();
            JSONArray content = message.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type")))
                    text.append(block.optString("text"));
            }
            return new JSONObject(text.toString());
        } catch (JSONException ex) {
            throw new AiException("Claude's answer couldn't be read. Try again.");
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws IOException {
        String service = serviceName(provider);
        if (TextUtils.isEmpty(key))
            throw new AiException("Add your " + service + " API key under Settings → Integrations → Extensions → AI writing");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (ANTHROPIC.equals(provider)) {
            headers.put("x-api-key", key);
            headers.put("anthropic-version", ANTHROPIC_VERSION);
            if (body != null && body.has("fallbacks"))
                headers.put("anthropic-beta", FALLBACK_BETA);
        } else
            headers.put("Authorization", "Bearer " + key);

        ExtHttp.Response response = ExtHttp.send(context, service, method,
                (ANTHROPIC.equals(provider) ? ANTHROPIC_URL : OPENAI_URL) + path,
                headers, body == null ? null : body.toString(), key, TIMEOUT);
        if (!response.ok())
            throw new AiException(explain(service, response));
        try {
            return new JSONObject(response.text);
        } catch (JSONException ex) {
            throw new AiException("Unexpected response from " + service);
        }
    }

    // Running out of credit or reaching a spending limit also comes back as 429, but waiting won't help with those.
    private static final List<String> BILLING_CODES = Arrays.asList("insufficient_quota", "credit_balance_exhausted",
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded");

    private static String explain(String service, ExtHttp.Response response) {
        int status = response.status;
        String message = "";
        String code = "";
        String type = "";
        try {
            JSONObject error = new JSONObject(response.text).optJSONObject("error");
            if (error != null) {
                message = error.optString("message");
                code = error.optString("code");
                type = error.optString("type");
            }
        } catch (JSONException ignored) {
            // Not JSON, so the message is made from the status alone.
        }
        if (status == 401)
            return service + " rejected your API key";
        if (status >= 300 && status < 400)
            return service + " redirected the request, so it wasn't sent on";
        if (status == 404)
            return message.isEmpty() ? service + " doesn't know that model" : message;
        if (status == 429 && (BILLING_CODES.contains(code) || "insufficient_quota".equals(type)))
            return "Your " + service + " credit is used up, or a spending limit is reached. Add credit under Billing on their site.";
        if (status == 429)
            return "Too many requests to " + service + ", or your spending limit is reached. Wait a moment and try again.";
        if (status == 503 || status == 529)
            return service + " is overloaded right now. Try again in a moment.";
        return message.isEmpty() ? service + " returned HTTP " + status : message;
    }
}
