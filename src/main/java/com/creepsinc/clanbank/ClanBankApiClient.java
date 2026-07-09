package com.creepsinc.clanbank;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to the clan bank bot's local API — read-only status fetches, plus
 * borrow/return/pay-loan actions that go through the exact same validation
 * and officer-approval flow as their Discord slash-command equivalents.
 * Nothing here bypasses approval; it just submits the request from the
 * game client instead of Discord.
 */
@Singleton
public class ClanBankApiClient
{
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public ClanBankApiClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	private HttpUrl urlFor(String baseUrl, String path) throws IOException
	{
		HttpUrl parsedBase = HttpUrl.parse(baseUrl);
		if (parsedBase == null)
		{
			throw new IOException("Invalid API URL: " + baseUrl);
		}
		return parsedBase.newBuilder().addPathSegment(path).build();
	}

	private <T> T get(String baseUrl, String apiKey, String path, Map<String, String> query, Class<T> type) throws IOException
	{
		HttpUrl.Builder urlBuilder = urlFor(baseUrl, path).newBuilder();
		for (Map.Entry<String, String> entry : query.entrySet())
		{
			urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
		}

		Request request = new Request.Builder()
			.url(urlBuilder.build())
			.header("x-api-key", apiKey == null ? "" : apiKey)
			.build();

		return execute(request, type);
	}

	private <T> T post(String baseUrl, String apiKey, String path, Map<String, Object> body, Class<T> type) throws IOException
	{
		RequestBody requestBody = RequestBody.create(JSON, gson.toJson(body));
		Request request = new Request.Builder()
			.url(urlFor(baseUrl, path))
			.header("x-api-key", apiKey == null ? "" : apiKey)
			.post(requestBody)
			.build();

		return execute(request, type);
	}

	private <T> T execute(Request request, Class<T> type) throws IOException
	{
		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.body() == null)
			{
				throw new IOException("Bot API returned HTTP " + response.code() + " with no body");
			}
			String body = response.body().string();

			if (!response.isSuccessful())
			{
				ApiErrorResponse err = gson.fromJson(body, ApiErrorResponse.class);
				String message = (err != null && err.error != null) ? err.error : ("Bot API returned HTTP " + response.code());
				throw new ClanBankApiException(message, err == null ? null : err.options, err == null ? null : err.suggestions);
			}

			return gson.fromJson(body, type);
		}
		catch (JsonSyntaxException e)
		{
			throw new IOException("Bad response from bot API", e);
		}
	}

	public ClanBankStatus fetchStatus(String baseUrl, String apiKey, String rsn) throws IOException
	{
		Map<String, String> query = new HashMap<>();
		query.put("rsn", rsn);
		return get(baseUrl, apiKey, "status", query, ClanBankStatus.class);
	}

	public ItemLookupResult lookupItem(String baseUrl, String apiKey, String itemName) throws IOException
	{
		Map<String, String> query = new HashMap<>();
		query.put("name", itemName);
		return get(baseUrl, apiKey, "item", query, ItemLookupResult.class);
	}

	public SearchResponse search(String baseUrl, String apiKey, String queryText) throws IOException
	{
		Map<String, String> query = new HashMap<>();
		query.put("query", queryText);
		return get(baseUrl, apiKey, "search", query, SearchResponse.class);
	}

	public ActionResponse submitBorrow(String baseUrl, String apiKey, String rsn, String itemName, int quantity, String finalProduct)
		throws IOException
	{
		Map<String, Object> body = new HashMap<>();
		body.put("rsn", rsn);
		body.put("itemName", itemName);
		body.put("quantity", quantity);
		body.put("finalProduct", finalProduct);
		return post(baseUrl, apiKey, "borrow", body, ActionResponse.class);
	}

	public ActionResponse submitReturn(String baseUrl, String apiKey, String rsn, String itemName, int quantity) throws IOException
	{
		Map<String, Object> body = new HashMap<>();
		body.put("rsn", rsn);
		body.put("itemName", itemName);
		body.put("quantity", quantity);
		return post(baseUrl, apiKey, "return", body, ActionResponse.class);
	}

	public ActionResponse submitPayLoan(String baseUrl, String apiKey, String rsn, String itemName, int amountGp) throws IOException
	{
		Map<String, Object> body = new HashMap<>();
		body.put("rsn", rsn);
		body.put("itemName", itemName);
		body.put("amountGp", amountGp);
		return post(baseUrl, apiKey, "pay-loan", body, ActionResponse.class);
	}
}
