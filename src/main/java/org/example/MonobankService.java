package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * MonobankService — safe API integration for Telegram bot.
 * Fetches rates for USD, EUR, and CNY to UAH.
 */
public class MonobankService {

  private static final String API_URL = "https://api.monobank.ua/bank/currency";

  public static String getExchangeRates() {
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(API_URL))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        return "❌ Monobank API error: " + response.statusCode();
      }

      JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();

      double usdBuy = 0, usdSell = 0;
      double eurBuy = 0, eurSell = 0;
      double cnyBuy = 0, cnySell = 0;

      for (JsonElement e : array) {
        JsonObject obj = e.getAsJsonObject();
        int codeA = obj.get("currencyCodeA").getAsInt();
        int codeB = obj.get("currencyCodeB").getAsInt();
        if (codeB != 980) continue;

        double buy = getSafeDouble(obj, "rateBuy");
        double sell = getSafeDouble(obj, "rateSell");

        switch (codeA) {
          case 840 -> { usdBuy = buy; usdSell = sell; }
          case 978 -> { eurBuy = buy; eurSell = sell; }
          case 156 -> { cnyBuy = buy; cnySell = sell; }
        }
      }

      return String.format("""
                    💰 *Monobank Currency Rates*
                    💵 USD: %.2f / %.2f ₴
                    💶 EUR: %.2f / %.2f ₴
                    🇨🇳 CNY: %.2f / %.2f ₴
                    (via api.monobank.ua)
                    """,
          usdBuy, usdSell, eurBuy, eurSell, cnyBuy, cnySell
      );

    } catch (Exception e) {
      e.printStackTrace();
      return "❌ Failed to fetch rates from Monobank.";
    }
  }

  private static double getSafeDouble(JsonObject obj, String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      try {
        return obj.get(key).getAsDouble();
      } catch (Exception ignored) {}
    }
    return 0;
  }
}
