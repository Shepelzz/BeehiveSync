# API-контракт: синк игроков Minecraft → сайт Beehive

## Обзор

Paper-плагин собирает данные об игроках и отправляет их на сайт по HTTP POST раз в N минут. Сайт сохраняет данные в MySQL для отображения на странице legends.

## Эндпоинт

```
POST https://beehiveprv.club/api/sync.php
Content-Type: application/json
Authorization: Bearer <TOKEN>
```

`TOKEN` — строка 64 hex-символа. Одинаковая в конфиге плагина и в `config.local.php` на сайте.

## Тело запроса

```json
{
  "players": [
    {
      "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "name": "Notch",
      "skin_head": "<base64 PNG 64x64>",
      "playtime_minutes": 12450,
      "achievements": 42,
      "is_sponsor": false,
      "is_banned": false,
      "is_moderator": false,
      "is_admin": true,
      "kills": 156,
      "deaths": 23,
      "distance_km": 892.45
    }
  ]
}
```

### Поля игрока

| Поле | Тип | Обязательное | Описание |
|------|-----|:---:|----------|
| `uuid` | string | да | UUID игрока (с дефисами или без). Нормализуется сервером в 32-char lowercase hex |
| `name` | string | да | Текущий никнейм, макс 32 символа |
| `skin_head` | string/null | нет | Base64-encoded PNG 64×64 лица игрока (см. раздел "Скины") |
| `playtime_minutes` | int | нет | Наигранное время в минутах. `Statistic.PLAY_ONE_MINUTE` / 20 / 60 |
| `achievements` | int | нет | Количество выполненных адванcements |
| `is_sponsor` | bool | нет | Имеет роль спонсора (permission или LuckPerms группа) |
| `is_banned` | bool | нет | Забанен |
| `is_moderator` | bool | нет | Роль модератора |
| `is_admin` | bool | нет | Роль администратора |
| `kills` | int | нет | `Statistic.PLAYER_KILLS` |
| `deaths` | int | нет | `Statistic.DEATHS` |
| `distance_km` | float | нет | Суммарное пройденное расстояние в км. Сумма статистик: `WALK_ONE_CM + SPRINT_ONE_CM + SWIM_ONE_CM + FLY_ONE_CM + CLIMB_ONE_CM + BOAT_ONE_CM + MINECART_ONE_CM + HORSE_ONE_CM + PIG_ONE_CM + STRIDER_ONE_CM + AVIATE_ONE_CM` / 100000 |

## Ответ

### Успех (200)
```json
{
  "ok": true,
  "updated": 15
}
```

### Частичный успех (200 с ошибками)
```json
{
  "ok": true,
  "updated": 13,
  "errors": [
    "Player #3: invalid uuid or name"
  ]
}
```

### Ошибки

| HTTP код | Ответ | Причина |
|----------|-------|---------|
| 400 | `{"error": "Expected JSON: {\"players\": [...]}"}` | Некорректное тело |
| 400 | `{"error": "Too many players in one request (max 500)"}` | Лимит превышен |
| 401 | `{"error": "Missing authorization"}` | Нет заголовка Authorization |
| 403 | `{"error": "Invalid token"}` | Неверный токен |
| 405 | `{"error": "Method not allowed"}` | Не POST |
| 500 | `{"error": "Server misconfigured"}` | Нет config.local.php или токен не задан |

## Получение скина головы

Плагин должен:

1. При `PlayerJoinEvent` получить текстуру из профиля:
```java
GameProfile profile = ((CraftPlayer) player).getProfile();
Property textures = profile.getProperties().get("textures").iterator().next();
String json = new String(Base64.getDecoder().decode(textures.getValue()));
// json содержит {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/..."}}}
```
Это работает и для лицензионных, и для пиратских игроков (SkinsRestorer подставляет текстуру в профиль).

2. Скачать текстуру по URL (это 64×64 PNG).

3. Вырезать лицо: пиксели **8,8 → 16,16** (первый слой) и наложить **40,8 → 48,16** (overlay/шапка), если не пустой.

4. Масштабировать 8×8 → 64×64 (nearest neighbor, без сглаживания).

5. Закодировать результат в base64 (без префикса `data:image/png;base64,`).

Пример на Java:
```java
BufferedImage skin = ImageIO.read(new URL(skinUrl));
BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
Graphics2D g = face.createGraphics();
g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);   // base layer
g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);   // overlay
g.dispose();

BufferedImage scaled = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
Graphics2D g2 = scaled.createGraphics();
g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
g2.drawImage(face, 0, 0, 64, 64, null);
g2.dispose();

ByteArrayOutputStream baos = new ByteArrayOutputStream();
ImageIO.write(scaled, "png", baos);
String base64Head = Base64.getEncoder().encodeToString(baos.toByteArray());
```

## Рекомендации по реализации плагина

### Расписание синка
- Отправлять данные раз в **5–10 минут** через `BukkitScheduler.runTaskTimerAsynchronously()`
- HTTP-запрос делать в async-таске, не блокировать main thread

### Конфиг плагина (config.yml)
```yaml
sync:
  url: "https://beehiveprv.club/api/sync.php"
  token: "тот_же_токен_что_в_config.local.php_на_сайте"
  interval-minutes: 5
```

### Определение ролей
Через LuckPerms API или Vault permissions:
```java
boolean isSponsor = player.hasPermission("group.sponsor");
boolean isModerator = player.hasPermission("group.moderator");
boolean isAdmin = player.hasPermission("group.admin") || player.hasPermission("group.owner");
boolean isBanned = Bukkit.getBanList(BanList.Type.NAME).isBanned(player.getName());
```

### Адвансменты
```java
int count = 0;
Iterator<Advancement> it = Bukkit.advancementIterator();
while (it.hasNext()) {
    Advancement adv = it.next();
    if (player.getAdvancementProgress(adv).isDone()) count++;
}
или как-то по человечески без перебора....
```

### HTTP-клиент
Использовать `java.net.http.HttpClient` (Java 11+):
```java
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(syncUrl))
    .header("Content-Type", "application/json")
    .header("Authorization", "Bearer " + token)
    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
    .build();
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

### Кеширование скинов
Скин не меняется часто. Кешируй base64 в `HashMap<UUID, String>` в памяти и обновляй только при `PlayerJoinEvent`. Не нужно пересчитывать каждый цикл синка.

## Публичный API для чтения (сайт)

```
GET https://beehiveprv.club/api/players.php          — все игроки (без забаненных, отсортированы по playtime)
GET https://beehiveprv.club/api/players.php?name=Nick — один игрок по имени
```

Ответ: JSON-массив или объект с полями из таблицы.

## Настройка на хостинге

1. Выполнить `api/migrate.sql` в phpMyAdmin
2. Скопировать `config.local.example.php` → `config.local.php`
3. Сгенерировать токен: `php -r "echo bin2hex(random_bytes(32));"`
4. Вставить токен в `config.local.php`
5. Тот же токен вставить в `config.yml` плагина
6. Убедиться что `config.local.php` НЕ в git (есть в `.gitignore`)
