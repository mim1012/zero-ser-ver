package com.zero.traffic.server;

import com.zero.traffic.util.Logger;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 서버 API 통합 클라이언트
 *
 * 모든 API 호출을 중앙 관리 (base URL + OkHttp)
 */
public class ApiClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient http;

    public ApiClient(String serverUrl) {
        this.baseUrl = serverUrl.endsWith("/")
                ? serverUrl.substring(0, serverUrl.length() - 1)
                : serverUrl;

        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    // ── GET JSON ────────────────────────────────────────

    public JSONObject getJSON(String path) throws IOException {
        String url = baseUrl + path;
        Request req = new Request.Builder().url(url).get().build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + url);
            }
            String body = resp.body() != null ? resp.body().string() : "{}";
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    // ── GET Text ────────────────────────────────────────

    public String getText(String path) throws IOException {
        String url = baseUrl + path;
        Request req = new Request.Builder().url(url).get().build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + url);
            }
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    // ── POST JSON ───────────────────────────────────────

    public JSONObject postJSON(String path, JSONObject body) throws IOException {
        String url = baseUrl + path;
        RequestBody reqBody = RequestBody.create(body.toString(), JSON_TYPE);
        Request req = new Request.Builder().url(url).post(reqBody).build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + url);
            }
            String respBody = resp.body() != null ? resp.body().string() : "{}";
            return new JSONObject(respBody);
        } catch (Exception e) {
            throw new IOException("POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
