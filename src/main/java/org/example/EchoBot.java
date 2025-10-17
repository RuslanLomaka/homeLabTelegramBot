package org.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class EchoBot {
  private static final Map<Long, String> userModes = new HashMap<>();
  private static final Map<Long, AgeSession> ageSessions = new HashMap<>();

  public static void main(String[] args) throws Exception {
    String botToken = System.getenv("BOT_TOKEN");
    System.out.println("BOT_TOKEN from env = " + botToken);

    OkHttpTelegramClient telegramClient = new OkHttpTelegramClient(botToken);

    try (TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication()) {
      botsApplication.registerBot(botToken, new LongPollingUpdateConsumer() {
        @Override
        public void consume(List<Update> updates) {
          for (Update update : updates) {
            if (update.hasMessage() && update.getMessage().hasText()) {
              long chatId = update.getMessage().getChatId();
              String text = update.getMessage().getText().trim();

              // START COMMAND
              if (text.equals("/start")) {
                sendMenu(chatId, telegramClient, "👋 Welcome! Choose a mode below:");
                continue;
              }

              // 💰 NEW BUTTON — Currency Rates
              if (text.equals("💰 Currency Rates")) {
                String rates = getMonobankRates();
                sendMenu(chatId, telegramClient, rates);
                continue;
              }

              // OTHER BUTTONS
              if (text.equals("🗣 Echo Mode")) {
                userModes.put(chatId, "ECHO");
                sendMenu(chatId, telegramClient, "✅ Echo Mode activated.");
                continue;
              } else if (text.equals("🔁 Reverse Mode")) {
                userModes.put(chatId, "REVERSE");
                sendMenu(chatId, telegramClient, "✅ Reverse Mode activated.");
                continue;
              } else if (text.equals("🕓 Age in Seconds")) {
                userModes.put(chatId, "AGE");
                AgeSession session = new AgeSession();
                ageSessions.put(chatId, session);
                sendPlain(chatId, telegramClient, "📅 Enter your birth year (e.g. 1990):");
                continue;
              }

              // MODE BEHAVIOR
              String mode = userModes.getOrDefault(chatId, "ECHO");

              if (mode.equals("ECHO")) {
                sendWithMenu(chatId, telegramClient, "Echo: " + text);
              } else if (mode.equals("REVERSE")) {
                sendWithMenu(chatId, telegramClient, "Reverse: " + new StringBuilder(text).reverse());
              } else if (mode.equals("AGE")) {
                handleAgeMode(chatId, text, telegramClient);
              }
            }
          }
        }
      });

      System.out.println("🤖 Bot is running... Press Ctrl+C to stop.");
      Thread.currentThread().join();
    }
  }

  // ========== MONOBANK API ==========
  private static String getMonobankRates() {
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("https://api.monobank.ua/bank/currency"))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200)
        return "❌ Monobank API error: " + response.statusCode();

      JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
      double usdBuy = 0, usdSell = 0, eurBuy = 0, eurSell = 0, cnyBuy = 0, cnySell = 0;

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
          usdBuy, usdSell, eurBuy, eurSell, cnyBuy, cnySell);

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

  // ========== AGE MODE LOGIC ==========
  private static void handleAgeMode(long chatId, String text, OkHttpTelegramClient client) {
    AgeSession s = ageSessions.getOrDefault(chatId, new AgeSession());
    LocalDateTime now = LocalDateTime.now();
    try {
      if (s.year == null) {
        int year = Integer.parseInt(text);
        int currentYear = now.getYear();
        if (year > currentYear) throw new IllegalArgumentException("❌ Year is in the future.");
        if (year < currentYear - 200) throw new IllegalArgumentException("❌ That’s over 200 years ago!");
        s.year = year;
        ageSessions.put(chatId, s);
        sendMonthMenu(chatId, client, "✅ Year saved. Now choose month:");
        return;
      }

      if (s.month == null) {
        Integer month = monthFromName(text);
        if (month == null) {
          sendMonthMenu(chatId, client, "❌ Please choose month using buttons:");
          return;
        }
        s.month = month;
        ageSessions.put(chatId, s);
        sendPlain(chatId, client, "📆 Enter day of month (1–31):");
        return;
      }

      if (s.day == null) {
        int day = Integer.parseInt(text);
        YearMonth ym = YearMonth.of(s.year, s.month);
        if (day < 1 || day > ym.lengthOfMonth())
          throw new IllegalArgumentException("❌ That date doesn’t exist in that month.");
        s.day = day;
        s.waitingForTimeChoice = true;
        ageSessions.put(chatId, s);
        sendTimeChoice(chatId, client);
        return;
      }

      if (s.waitingForTimeChoice) {
        if (text.equals("Yes 🕐")) {
          s.waitingForTimeChoice = false;
          s.waitingForHour = true;
          sendPlain(chatId, client, "⌚ Enter hour (0–23):");
          return;
        } else if (text.equals("Skip ⏭️")) {
          s.hour = 0;
          s.minute = 0;
          finishAgeCalculation(chatId, client, s);
          ageSessions.remove(chatId);
          return;
        } else {
          sendTimeChoice(chatId, client);
          return;
        }
      }

      if (s.waitingForHour) {
        int hour = Integer.parseInt(text);
        if (hour < 0 || hour > 23)
          throw new IllegalArgumentException("❌ Hour must be 0–23.");
        s.hour = hour;
        s.waitingForHour = false;
        s.waitingForMinute = true;
        sendPlain(chatId, client, "🕐 Enter minute (0–59):");
        return;
      }

      if (s.waitingForMinute) {
        int minute = Integer.parseInt(text);
        if (minute < 0 || minute > 59)
          throw new IllegalArgumentException("❌ Minute must be 0–59.");
        s.minute = minute;
        finishAgeCalculation(chatId, client, s);
        ageSessions.remove(chatId);
      }

    } catch (Exception e) {
      sendPlain(chatId, client, e.getMessage() + "\nPlease try again:");
    }
  }

  private static void finishAgeCalculation(long chatId, OkHttpTelegramClient client, AgeSession s) {
    LocalDateTime birth = LocalDateTime.of(s.year, s.month, s.day, s.hour, s.minute);
    LocalDateTime now = LocalDateTime.now();
    if (birth.isAfter(now)) {
      sendPlain(chatId, client, "❌ That’s a future date! Try again with correct values.");
      return;
    }

    long seconds = ChronoUnit.SECONDS.between(birth, now);
    long years = ChronoUnit.YEARS.between(birth, now);
    long days = ChronoUnit.DAYS.between(birth.plusYears(years), now);
    long hours = ChronoUnit.HOURS.between(birth.plusYears(years).plusDays(days), now);

    String msg = String.format(
        "🎉 You were born on %04d-%02d-%02d %02d:%02d\nYou are %,d seconds old! 🕓\n(≈ %d years, %d days and %d hours)",
        s.year, s.month, s.day, s.hour, s.minute, seconds, years, days, hours);

    sendMenu(chatId, client, msg);
  }

  private static Integer monthFromName(String text) {
    String t = text.toLowerCase();
    String[] names = {"jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"};
    for (int i = 0; i < names.length; i++) if (t.contains(names[i])) return i + 1;
    return null;
  }

  // ========== KEYBOARDS ==========
  private static ReplyKeyboardMarkup getMainMenuKeyboard() {
    KeyboardRow row1 = new KeyboardRow();
    row1.add(new KeyboardButton("🗣 Echo Mode"));
    row1.add(new KeyboardButton("🔁 Reverse Mode"));
    KeyboardRow row2 = new KeyboardRow();
    row2.add(new KeyboardButton("🕓 Age in Seconds"));
    row2.add(new KeyboardButton("💰 Currency Rates"));
    List<KeyboardRow> keyboard = Arrays.asList(row1, row2);
    return new ReplyKeyboardMarkup(keyboard, true, false, false, null, false);
  }

  private static void sendMonthMenu(long chatId, OkHttpTelegramClient client, String text) {
    List<KeyboardRow> keyboard = new ArrayList<>();
    String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
    for (int i = 0; i < 3; i++) {
      KeyboardRow row = new KeyboardRow();
      for (int j = 0; j < 4 && i * 4 + j < months.length; j++)
        row.add(new KeyboardButton(months[i * 4 + j]));
      keyboard.add(row);
    }
    ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(keyboard, true, false, false, null, false);
    SendMessage m = new SendMessage(Long.toString(chatId), text);
    m.setReplyMarkup(markup);
    try { client.execute(m); } catch (TelegramApiException e) { e.printStackTrace(); }
  }

  private static void sendTimeChoice(long chatId, OkHttpTelegramClient client) {
    KeyboardRow row = new KeyboardRow();
    row.add(new KeyboardButton("Yes 🕐"));
    row.add(new KeyboardButton("Skip ⏭️"));
    List<KeyboardRow> k = Collections.singletonList(row);
    ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(k, true, false, false, null, false);
    SendMessage m = new SendMessage(Long.toString(chatId), "⏰ Do you know the exact time you were born?");
    m.setReplyMarkup(markup);
    try { client.execute(m); } catch (TelegramApiException e) { e.printStackTrace(); }
  }

  private static void sendMenu(long chatId, OkHttpTelegramClient client, String text) {
    SendMessage message = new SendMessage(Long.toString(chatId), text);
    message.setReplyMarkup(getMainMenuKeyboard());
    try { client.execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
  }

  private static void sendWithMenu(long chatId, OkHttpTelegramClient client, String text) {
    sendMenu(chatId, client, text);
  }

  private static void sendPlain(long chatId, OkHttpTelegramClient client, String text) {
    SendMessage message = new SendMessage(Long.toString(chatId), text);
    try { client.execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
  }

  // SESSION DATA CLASS
  static class AgeSession {
    Integer year;
    Integer month;
    Integer day;
    Integer hour = 0;
    Integer minute = 0;
    boolean waitingForTimeChoice = false;
    boolean waitingForHour = false;
    boolean waitingForMinute = false;
  }
}
