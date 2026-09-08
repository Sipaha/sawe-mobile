# Аудит мобильного клиента: сетевая устойчивость и баги — 2026-09-05

Репозитории: мобильный клиент `/home/spk/.spk/sawe/ss/Sawe1/spk-editor-mobile` (Kotlin: `:core` — чистая JVM-библиотека сети, `:app` — Android Compose UI, `:cli` — smoke-клиент), десктопный сервер `/home/spk/.spk/sawe/ss/Sawe1/sawe` (Rust; `crates/remote_control`, `crates/solution_agent`, `crates/editor_mcp`). Пути в отчёте относительны корню соответствующего репозитория; серверные пути помечены префиксом `sawe/`. Строки указаны по состоянию кода на дату аудита.

> **Статус: находки исправлены 2026-09-06.** Отчёт сохранён как есть — это снимок состояния на
> момент аудита, и номера строк в разделе 4 относятся к коду *до* правок. Что именно сделано,
> что осталось и как это проверено — см. раздел 8 в конце файла.

## 1. Резюме

**Что аудировали.** Весь сетевой путь телефон ↔ десктоп: единственный TLS-pinned WebSocket (`wss://host:port/remote`, HMAC challenge handshake, JSON-RPC, DEFLATE с preset-словарём, `editor/notification`, chunked binary uploads); жизненный цикл соединения (`RemoteClient`, `ConnectionManager`, foreground/background, process death); офлайн-очередь и персистентность (`QueueController`, `EncryptedQueueStore`, `PendingSendsRepository`); дельта-синхронизацию транскрипта (`SessionDetailStore`, `get_session`/`get_session_changes`); загрузку вложений (`UploadManager`); Compose UI и репозитории; серверную сторону контракта (`listener.rs`, `proxy.rs`, `read.rs`, `dto.rs`, `event_sources.rs`, `upload.rs`).

**Метод.** 7 параллельных ревьюеров по скоупам (transport, queue, connmgr, sync, upload, ui, server) → 7 адверсариальных верификаторов, каждый перепроверял каждую находку по коду с обеих сторон, грепом call sites и, где позволял `:core`-стенд, временными репро-тестами на `FakeRemoteTransport`/`InMemoryQueueStore`/`StandardTestDispatcher` (transport: 7 тестов, queue: 8) → сведение в этот отчёт. Вердикт верификатора приоритетнее ревьюера; по каждому расхождению в отчёте указана коррекция.

**Базовое состояние.** Сборка зелёная; `:core` 332 теста, `:app` 41 тест, всё зелёное. Репро-тесты после прогона удалены, `git status --porcelain` пуст.

**Итог после верификации и дедупликации:** 67 подтверждённых находок — **10 HIGH, 27 MEDIUM, 30 LOW**; 4 находки отклонены (приложение A), 7 понижены, 2 повышены.

**Главные выводы.**
1. Устойчивый reconnect-цикл в `:core` есть, но приложение теряет его на краях: неудача *первой* попытки соединения (N-09), восстановление после process death (N-10) и JSON-RPC-ошибка на `capabilities` (N-11) оставляют UI в мёртвом состоянии до force-stop.
2. Офлайн-очередь на диске надёжна ровно до тех пор, пока не вызван `RemoteClient.close()` — любой graceful-выход, смена или правка сервера удаляют неотправленные сообщения без bounce в черновик (N-02); отправка в connected-режиме при обрыве в окне RTT просто теряется (N-01).
3. Стриминговый трафик квадратичен: `get_session_changes` шлёт весь entry на каждый poll, клиент отменяет in-flight poll на каждый poke, а цикл сходимости целится в недостижимый глобальный `change_seq` — вместе это даёт poll-шторм 1/RTT с полным телом ответа в каждом кадре (N-29).
4. Фотографии ходят как base64 внутри entries и запрашиваются на каждом poll/странице — включая только что загруженные телефоном; декодируются в composition в полном разрешении (N-30).
5. Два несогласованных keepalive по 30 с (WS ping + RPC `capabilities`), heartbeat и reconnect-цикл в фоне, отсутствие `ConnectivityManager` — двойной расход радио без выигрыша в детекции (N-31, N-19, N-16).
6. Загрузка вложений: транзиентные ошибки на `upload_finish`, `unknown_upload_id` и истечение 5-минутного wall-clock таймаута считаются терминальными, после чего текст и вложения пользователя выбрасываются (N-04, N-40, N-41, N-43).
7. Серверный цикл соединения строго последовательный: один медленный RPC или большой кадр блокирует ping/pong, уведомления и все остальные RPC, а очередь уведомлений при переполнении теряет *новейший* кадр (N-50, N-52).

## 2. Топ приоритетов

| # | id | severity | Почему |
|---|----|----------|--------|
| 1 | N-09 | HIGH | Неудача первой попытки connect (типично на LTE/при спящем ноутбуке) → observer не установлен, workspace вечно `Loading`, banner врёт, re-scan QR — no-op; выход только force-stop. |
| 2 | N-10 | HIGH | После process death (Doze/OOM) приложение вообще не пытается соединиться — спиннер навсегда. |
| 3 | N-02 | HIGH | `close()` удаляет офлайн-очередь с диска без bounce: правка адреса сервера, смена сервера, `onCleared` — сообщения исчезают бесследно. |
| 4 | N-01 | HIGH | Обрыв сокета в окне RTT после отправки в Connected → текст потерян, поле уже очищено. |
| 5 | N-29 | HIGH | Poll-шторм: whole-entry re-send × cancel-and-rearm × недостижимая цель сходимости — до 15 req/s на Wi-Fi, ответы с полным телом entry. |
| 6 | N-30 | HIGH | Base64-картинки в каждом delta/page, полноразмерный decode в composition → многомегабайтные ответы, таймауты 30 с, OOM-риск. |
| 7 | N-11 | HIGH | Error-envelope на `capabilities` (MCP-прокси не поднят) декодируется как `wire_schema_version = 0` → терминальный экран «server too old» без retry. |
| 8 | N-04 / N-40 | HIGH | Отложенная отправка с вложениями: любой сбой (в т.ч. транзиентный на `upload_finish`) выбрасывает текст и вложения, ошибка может быть подавлена 4-секундным gate. |
| 9 | N-03 | HIGH | Осиротевшие маркеры `PendingSendsRepository` → вечные фантомные «Sending»-пузыри, переживающие рестарты. |
| 10 | N-31 / N-19 / N-16 | MEDIUM | Двойной keepalive, фоновый heartbeat+reconnect, нет реакции на смену сети — батарея и медленное восстановление. |
| 11 | N-50 / N-52 | MEDIUM | Серверный serial loop и drop-newest в очереди уведомлений — HOL-блокировка ping/RPC, потеря `upload_chunk_acked` и «лечащего» `dirty`. |

## 3. Что уже сделано хорошо

Подтверждённые верификаторами сильные места (из «Positive observations» ревьюеров; опровергнутые пункты исключены или снабжены оговоркой):

- **Пары таймаутов согласованы:** OkHttp WS ping 30 с < серверный `IDLE_READ_TIMEOUT_SECS = 60`; сервер считает любой inbound-кадр (включая Ping) активностью; пропущенный pong классифицируется как транзиентный `Unreachable`.
- **Транспорт публикуется только после `welcome`** (`RemoteClient.kt:773-799`); поздние события от вытесненных сокетов фильтруются `boundTx`/`isStale`; словарь компрессии сбрасывается на каждую попытку.
- **Pending-вызовы отказываются на каждом пути потери транспорта** (`awaitDisconnect` → `failPendingOnDisconnect`), `close()` и `callInternal` сериализованы через `stateLock`; id запросов монотонны через reconnect'ы, поздний ответ не может совпасть с новым вызовом.
- **Офлайн-очередь:** persist-before-enqueue под `stateLock` с `commit()`, accumulate-then-restore FIFO с восстановлением в исходном порядке (покрыто тестом), три пути TTL-истечения с bounce в черновик, самовосстановление при повреждённом blob'е.
- **Компрессия:** словарь байт-в-байт идентичен на обеих сторонах (1612 байт, Adler-32 639723996 проверен программно); проверки declared-length, truncation, trailing data, cap 32 MiB; никогда не раздувает кадр; handshake-кадры несжаты.
- **`ConnectionManager`:** все переходы под `connectionMutex`, `RemoteClient` одноразовый и пересоздаётся на switch, старые collectors/heartbeat отменяются, `forceReconnect` с 12-секундным grace и CONFLATED wake-канал — foreground-штормов reconnect'ов нет; resubscribe выполняется до `Connected`.
- **Сервер:** pre-auth ошибки транспорта не идут в ban-ladder, первая неудача — grace; 1-client-1-connection kick на свежем auth (нужное поведение после Doze/смены IP); undecodable compressed-кадры отбрасываются без закрытия сокета, unparsable JSON получает `-32700`; редактор никогда не блокируется медленным телефоном (unbounded канал до proxy, drop в proxy).
- **Дельта-протокол:** per-stream курсор, epoch-guard (`reset`), пагинация по `mod_seq` (10/страница, `has_more`), персистентный курсор → холодный старт = дельта, а не полный fetch; единственный applier под `sessionMutex` со stale-session барьером; `WorkspaceStore` — корректный sequenced-delta mirror (dedupe, gap → буфер + resync).
- **Загрузка:** файл не буферизуется целиком (один 256 KiB буфер), stop-and-wait с server-authoritative resume (`upload_status`), персист offset на каждый ack, `pauseAll` на falling edge и `resumeAll` на rising edge, watchdog для зависшего Paused, `upload_init` с бесконечным jittered backoff и корректным rethrow `CancellationException`; серверные лимиты (5 MiB, 4/сессия, 1 h TTL) ограничивают ресурсы.
- **Compose:** стабильные ключи LazyColumn, `remember`-кэш парсинга markdown per bubble, latched follow-the-newest (стриминг не сдвигает прокрутившего вверх пользователя), debounced-запись черновика на IO + flush на dispose, двухстадийный `ConnectionStatusBanner` (тихие 15 с, потом громкий текст), `NavStateRepository` с одноразовым restore и деградацией мёртвого маршрута, `CrashLogger` с цепочкой к прежнему handler'у, `addServer` объединяет re-pair по fingerprint сертификата.
- С оговорками: `ForegroundEventBus` + `probeLivenessNow` действительно ловят zombie-сокет после Doze, но проба однострайковая (N-15); 4-секундный gate снэкбара `sendError` убирает противоречие с баннером, но заодно глушит ошибки отложенной отправки (N-04); дисковые маркеры optimistic-пузырей переживают process death, но не имеют GC (N-03).

## 4. Находки по темам

Формат записи: `id · severity · категория · сторона`. «Источники» — исходные id ревьюеров и вердикты верификаторов (сырые файлы `findings-*.md` / `verify-*.md` сохранены вне репозитория: `<solution-root>/.agents/audit-2026-09-05-mobile-raw/`). Severity — после верификации.

### 4.1 Потеря / дублирование сообщений

#### N-01 · HIGH · network-resilience · client
**Источники:** queue H1 (CONFIRMED; репро H1, H1b воспроизведены).

**Описание.** `QueueController.queueCall` уходит в дисковую очередь только при `NotConnectedException` (`core/src/main/kotlin/ru/sipaha/sawe/core/QueueController.kt:132-138`, `:168-181`). Все прочие формы потери транспорта — кадр передан OkHttp и сокет умер до ответа (`RemoteClient.kt:871-875`, `failPendingOnDisconnect` → `IllegalStateException("connection closed: …")`), `send()` вернул `false` (`RemoteClient.kt:434-437`, `"websocket refused frame"`), zombie-сокет, убитый watchdog'ом через ~76 с — доходят до `SessionDetailStore.sendMessageBlocks.onFailure` (`app/src/main/kotlin/ru/sipaha/sawe/app/vm/SessionDetailStore.kt:1526-1536`), который удаляет маркер и пузырь, показывает тост и **не** вызывает `DraftRepository.setBounced` (единственный вызов `setBounced` — TTL-путь `handleExpiredMessage`, `:536-540`). Поле ввода уже очищено `clearDraft` при отправке (`SessionDetailScreen.kt:531-536`). На fast-path нет и таймаута: `QueueController.callRpc` = `callInternal` (`RemoteClient.kt:246`), без `withTimeout`.

**Сценарий отказа.** LTE, состояние `Connected`, пользователь нажимает Send; NAT rebinding / handover Wi-Fi→LTE убивает сокет через 200 мс, до прихода JSON-RPC-ответа → тост «connection closed: …», пузырь исчез, поле пустое. Если кадр не дошёл до сервера — сообщение потеряно; если дошёл — оно появится через poll, тогда как UI только что сообщил об ошибке.

**Верификация.** Репро `H1` (Connected → `queueCall` → кадр на tx1 → `closeFromServer` до ответа → reconnect): `H1 result=Failure(java.lang.IllegalStateException: connection closed: Server closed the connection (code 1000): nat rebinding) store=0 expired=0` / `H1 resentOnTx2=false tx2.sent=1`. Репро `H1b` (транспорт отказал кадру в Connected): `result=Failure(java.lang.IllegalStateException: websocket refused frame) store=0 state=Connected`. Сервер: `sawe/crates/remote_control/src/proxy.rs:41` гарантирует ответ за 30 с на живом сокете; идемпотентности по `spk_client_send_id` нет (см. N-05), поэтому слепой retry сегодня небезопасен.

**Исправление (client, затем both).** Минимально и безопасно: в `sendMessageBlocks.onFailure` для любой не-TTL причины вызывать `draftRepository.setBounced(sessionId, text)` — текст никогда не теряется. Полноценно: в `queueCall` трактовать `IllegalStateException` от потери транспорта как `NotConnectedException` и парковать тот же `QueuedMessage` (тот же csid) в дисковую очередь — но только после серверного dedupe по `spk_client_send_id` (N-05).

#### N-02 · HIGH · persistence · client
**Источники:** queue H2 (CONFIRMED; репро H2, H2b воспроизведены) = connmgr H3 (CONFIRMED) = ui H3 (CONFIRMED, триггер скорректирован).

**Описание.** `RemoteClient.close()` (`core/src/main/kotlin/ru/sipaha/sawe/core/RemoteClient.kt:344-382`) снимает снимок `queued`, для каждого элемента вызывает `queueStore.remove(id)` и завершает deferred `ClosedException`; `onMessageExpired` не вызывается. Хранилище уже изолировано per-server (`EncryptedQueueStore.kt:247`, ключ `queued_messages_v2:<serverId>`), так что удаление не нужно для изоляции; обоснование в KDoc (`RemoteClient.kt:349-352`, «caller already observed as failed») не выполняется — единственный caller (`SessionDetailStore.kt:1526-1536`) никогда не повторяет и не восстанавливает текст. `close()` вызывается безусловно из `ConnectionManager.tearDownConnection` (`app/src/main/kotlin/ru/sipaha/sawe/app/vm/ConnectionManager.kt:500`), т.е. при `editServer` с изменённым host/port/secret/fingerprint (`:296-304` → `switchToServerLocked(force=true)` → `:327-368`), `switchToServer`, `removeServer` и `MainViewModel.onCleared` (`MainViewModel.kt:769-774`). Отягчающее (connmgr/ui): `ViewModel.clear()` сначала отменяет `viewModelScope`; `runCatching { queueCall }` в `sendMessageBlocks` (`SessionDetailStore.kt:1508-1510`) проглатывает `CancellationException`, и `.onFailure` (`:1520-1521`) синхронно удаляет маркер `PendingSendsRepository` и пузырь. Черновик очищен при отправке. Регидратированные записи из прошлого процесса удаляются тем же путём вообще без caller'а.

**Сценарий отказа.** LAN-IP десктопа сменился; телефон в `Reconnecting`; пользователь набирает два сообщения (оба на диске, пузыри с часами), идёт в список серверов и правит host → `editServer` → `close()` → оба удалены с диска, два тоста «send cancelled — connection closed», пузыри исчезли, поле пустое. Новое соединение поднимается — отправлять нечего. Вариант connmgr: телефон офлайн в метро, Send, выход из приложения → `onCleared` → то же, но без единого сообщения пользователю. **Коррекция ui-верификатора:** на API 31+ Back на корневой activity уводит задачу в фон, не финиширует её — сценарий «нажал Back дважды» удаляет очередь только на API 26-30; на всех API то же происходит при swipe из Recents (activity уничтожается при живом процессе) и «Don't keep activities». Force-kill, напротив, сохраняет запись на диске.

**Верификация.** Репро `H2` (drop → `queueCall` офлайн → store=1 → `close()`): `H2 result=Failure(ClosedException: client closed before flush) storeAfterClose=0 expired=0`. Репро `H2b` (предзаполненный store, `connect()` в ожидании handshake, `close()`): `storeAfterClose=0 expired=0` — сирота удалена без caller'а и без bounce. Все три верификатора сошлись: одна первопричина, считать одной находкой.

**Исправление (client).** `close()` должен только завершать in-memory deferred'ы и оставлять per-server записи на диске (следующий `RemoteClient` для этого сервера их регидратирует); настоящий wipe уже делают `forgetPairing`/`removeServer` через `clearFor(serverId)`/`removeForServer`. Если удаление зачем-то сохраняется — прогонять каждый элемент через `onMessageExpired`, чтобы текст попал в черновик. В `SessionDetailStore` перебрасывать `CancellationException` и не удалять маркер на нём; на `ClosedException` — bounce в черновик.

#### N-03 · HIGH · bug · client
**Источники:** queue H3 (CONFIRMED; сценарий 1 сужен, severity сохранена).

**Описание.** Текстовый маркер `PendingSendsRepository` пишется до `queueCall` (`SessionDetailStore.kt:1473`, `:1574`) и удаляется только (а) живой корутиной `sendMessageBlocks` при успехе/ошибке (`:1524`/`:1527`, `:1892`) или (б) reconcile, увидевшим csid в загруженных `serverEntries`/`pending_bundles` (`:1129`, `:1408`); ещё `removeForServer` при unpair (`MainViewModel.kt:474`). После process death (а) не существует: очередь регидратируется и воспроизводится/истекает в `QueueController` с осиротевшим deferred (`QueueController.kt:83-95`, `:228-232`, `:285-291`, `:353-363`) и `ConnectionManager.drainExpiredQueueEntries` (`ConnectionManager.kt:536-545`) — ни один из этих путей не знает о маркере. (б) срабатывает только если echo попал в tail-страницу из 50 записей (`SESSION_PAGE_SIZE`, `:62`, `:976`, `:991`) или в дельту, наблюдаемую при открытой сессии (`:1071`). В `PersistedPendingSend` нет timestamp (`PendingSendsRepository.kt:28-35`) — GC по возрасту невозможен. На каждый `openSession` маркер снова материализуется в optimistic `EntrySummary` с csid (`SessionDetailStore.kt:604-628`) и рендерится как Sending/Queued.

**Сценарий отказа.** (1) Отправка офлайн → маркер + запись очереди; приложение убито Doze/OOM; следующий запуск отправляет сообщение; агент делает длинный ход (>50 записей tool-calls); пользователь открывает сессию — настоящее сообщение выше загруженного окна, маркер создаёт «Sending»-пузырь внизу, который никогда не разрешается и возвращается на каждом открытии и после рестартов. (2) Телефон офлайн >24 ч при перезапущенном процессе → TTL-bounce кладёт текст в черновик (верно), маркер остаётся → вечный фантом плюс черновик. (3) Путь N-02 на регидратированных записях → то же.

**Верификация.** Трассировка кода (в `:core` не воспроизводимо): полный список писателей/удалителей маркера собран грепом, ни один TTL/close-путь маркер не трогает. **Коррекция:** сценарий (1) уже, чем описано — фантом переживает только если echo не попал ни в одну страницу/дельту при открытой сессии; (2) и (3) безусловны. HIGH сохранена: возникший фантом постоянен, снимается только unpair.

**Исправление (client).** На `openSession` материализовать только маркеры, чей csid ещё есть в `EncryptedQueueStore` (действительно неотправленные); добавить `enqueued_at` в `PersistedPendingSend` и отбрасывать маркеры старше TTL очереди в `list()`; удалять маркер по csid (восстановим из `params.blocks[0]._meta.spk_client_send_id`) на каждом терминальном пути очереди (`onMessageExpired`, close-drain).

#### N-04 · HIGH · bug (message loss) · client
**Источники:** upload H1 (CONFIRMED; формулировка про text-only путь скорректирована).

**Описание.** При Send с незавершённым вложением compose bar синхронно чистит `draft = ""` и `pickedAttachments = emptyList()` (`app/src/main/kotlin/ru/sipaha/sawe/app/ui/solutions/SessionDetailScreen.kt:3446-3457`); пустой список зеркалится в store через `snapshotFlow` (`:3051-3054` → `setPickedAttachments` → `persistAttachments([])`, `SessionDetailStore.kt:2344-2351`), т.е. дисковый черновик вложений тоже стирается. Каждый путь отказа `runDeferredSend` — `flow == null` (`:1685-1693`), `withTimeoutOrNull == null` (`:1735-1743`), `Failed` (`:1745-1753`), защитный `else` (`:1758-1766`), ошибка `queueCall` (`:1827-1836`) — идёт через `cleanupDeferred` (`:1886-1925`): удаление optimistic-пузыря, маркера, `forgetUpload` для всех вложений (→ `UploadManager.forget`, `:361-370`: снимает StateFlow, метаданные, дисковую запись, без abort) и только `context.emitError`. `setBounced` не вызывается; `PickedAttachment` не восстанавливаются. Вдобавок snackbar-gate (`SessionDetailScreen.kt:252-270`) отбрасывает ошибку, если в момент emit состояние не `Connected` и соединение вернулось за 4 с — а лифецикл ставит `Reconnecting` синхронно сразу после `awaitDisconnect` (`RemoteClient.kt:712 → 687-693`), до диспатча упавшего `upload_finish`, так что ошибка N-40 глушится именно в этом окне.

**Сценарий отказа.** Абзац текста + фото, Send при 60 % загрузки; Wi-Fi моргает 2 с во время `upload_finish` → upload `Failed` → `cleanupDeferred` → пузырь, текст и чип фото исчезли; соединение вернулось за 4 с — снэкбара нет. Пользователь должен заметить пропажу и перенабрать всё.

**Верификация.** Трассировка кода. **Коррекция:** текстовый путь тоже делает bounce только по TTL (не при `onFailure`), а отложенный путь тоже получает TTL-bounce первого текстового блока через `parseExpiredSendMessage` (`SessionEntryMerge.kt:334-346`); все прочие отказы отложенной отправки теряют текст и вложения, как описано.

**Исправление (client).** В `cleanupDeferred` при отказе — `draftRepository.setBounced(send.text)`, сохранять `AttachmentRef`'ы (не вызывать `forget()`, оставить upload в `Failed` с реальным retry, см. N-48); ошибки отложенной отправки проводить мимо 4-секундного gate.

#### N-05 · MEDIUM · network-resilience · both
**Источники:** queue M1 (CONFIRMED; репро M1 воспроизведён — клиентская половина).

**Описание.** В flush-пути любая не-TTL ошибка → `Failed(index)` → элемент восстанавливается в голову очереди и пересылается на следующем `Connected` (`QueueController.kt:292-310`, `:321-337`; `RemoteClient.kt:842-845`, `:868`); дисковая запись удаляется только после ответа (`:294`), так что process death в окне RTT тоже ведёт к повтору. Сервер не помнит csid: `send_message_blocks` в idle-сессии начинает ход, в running — добавляет в `pending_messages` (`sawe/crates/solution_agent/src/store/queue.rs:548-712`); `spk_client_send_id` только извлекается и эхом возвращается (`acp_thread.rs:245-278`, `event_sources.rs:290-326`, `dto.rs:392-410`, `upload.rs:524-531`, `messaging.rs:132-171` — ни одного lookup). Reconcile на телефоне снимает optimistic-пузырь по первому echo (`SessionEntryMerge.kt:266-272`); второй entry с тем же csid рендерится обычным пузырём.

**Сценарий отказа.** Линк моргает; reconnect; `onConnected` флашит «run the tests»; LTE падает через 300 мс до ответа → `Failed(0)` → restore → следующий reconnect шлёт снова. Сервер: первая копия запустила ход, вторая слилась в `pending_messages` как follow-up → агент гоняет тесты дважды, в транскрипте два сообщения.

**Верификация.** Репро `M1` (offline queue → reconnect → кадр на tx2 → drop до ответа → reconnect): `M1 firstSend=1 storeAfterDrop=1` / `M1 secondSend=1` — идентичные params (тот же csid) дважды на проводе. Серверная половина: `grep -rn "client_send_id\|csid" sawe/crates` — только извлечение/эхо.

**Исправление (server, additive).** Per-session LRU недавних `spk_client_send_id`; на повтор возвращать `structured_content: {duplicate: true}` вместо enqueue. Клиент уже сохраняет csid при replay — менять не нужно. До этого: помечать restored-after-in-flight элементы и показывать «may have been sent» вместо тихого повтора.

#### N-06 · MEDIUM · persistence / duplication · client
**Источники:** upload M4 (CONFIRMED; точный порядок холодного старта прослежен).

**Описание.** Запись pending-send удаляется только в `cleanupDeferred` (`SessionDetailStore.kt:1892`), т.е. после возврата `queueCall` (`:1815-1836`); офлайн-ветка `queueCall` персистит до ожидания (`QueueController.kt:151-167`). Process death в этом окне оставляет на диске и запись очереди, и pending-send. Порядок на следующем старте: `ConnectionManager.connectTo` (`ConnectionManager.kt:358-368`) строит `RemoteClient` (регидратирует очередь с осиротевшим deferred), вызывает `lifecycle.onClientBound` (`:367`) **до** `connect()` (`:368`) → `resumeAllFromDisk` + `resumeDeferredSendsFromDisk` (`MainViewModel.kt:303-322`; последняя оживляет только отправки с вложениями, `SessionDetailStore.kt:1616-1651`); затем handshake → `onConnected` → `flushQueue` шлёт `send_message_blocks` до `Connected` (`RemoteClient.kt:805, 856-869`); rising edge → `resumeAll` → `upload_status`. Сервер обрабатывает последовательно (`listener.rs:1131-1161`): `send_message_blocks` → `resolve_upload_handles` → `abort(id)` (`upload.rs:540-544`) завершается раньше `upload_status` → `unknown_upload_id` (`uploads.rs:166`) → `Failed("upload expired on server — re-attach")` → waiter → `cleanupDeferred(failure)` → пузырь удалён + тост об ошибке, хотя сообщение доставлено. Повторная отправка пользователем → дубликат (dedupe по csid нет, N-05).

**Сценарий отказа.** Фото дошло до Done в момент закрытия дверей лифта; `send_message_blocks` встал в офлайн-очередь; Android убил приложение через 2 мин; пользователь открывает его в зоне покрытия → сообщение появляется в чате И тост «upload failed / re-attach».

**Верификация.** Трассировка; текстовый путь явно защищён от двойного replay (комментарий `:1623-1631`), вложения — нет.

**Исправление (client).** Помечать pending-send как `queued` (или удалять) сразу после того, как `queueCall` поставил сообщение в очередь; `resumeDeferredSendsFromDisk` должен пропускать такие записи, зеркаля text-only исключение.

#### N-07 · MEDIUM · network-resilience (UX очереди) · client
**Источники:** ui M5 (CONFIRMED; порядок потери в сценарии инвертирован верификатором).

**Описание.** `userBubbleStatusFor` (`SessionDetailScreen.kt:1237-1285`) даёт только None/Uploading/Queued/Sending/Delivered — припаркованная офлайн-отправка показывается как `Sending` (или `Queued`) до 24 ч (`DEFAULT_QUEUE_TTL_MS`, `RemoteClient.kt:1152`); у строки статуса нет click/long-press (`:1643-…`), отменить/отредактировать нельзя. По истечении TTL пузырь удаляется, текст попадает в bounce-слот (`onMessageExpired → handleExpiredMessage`, `SessionDetailStore.kt:536-540`), но `bouncedFor` читается только в `loadDraftSeed` (`:2270-2273`) из `LaunchedEffect(sessionId)` (`SessionDetailScreen.kt:301-312`) — не вживую.

**Сценарий отказа (скорректированный).** Сообщение припарковано на ночь; утром пользователь в чате, TTL срабатывает → пузырь исчез; он набирает новое сообщение, позже переоткрывает сессию: поле стартует пустым (`rememberSaveable(sessionId)` сбрасывается на новом back-stack entry), `loadDraftSeed` предпочитает bounce дисковому черновику (`:2271-2272`), seed заполняет поле (`:3126-3131`), debounced-writer перезаписывает набранный черновик текстом bounce — теряется **новый** черновик (KDoc `:291-300` это признаёт). Обратная потеря (bounce отброшен) — только в ms-гонке, когда пользователь печатает до прихода seed.

**Верификация.** Трассировка кода; класс потери подтверждён, направление исправлено.

**Исправление (client).** Отдельное состояние «Waiting for connection» с long-press/× для отмены (удаление из `queueStore` + `PendingSendsRepository`); bounce для открытой сессии доставлять вживую (flow) и *дописывать* к текущему черновику, а не полагаться на seed-on-open.

#### N-08 · MEDIUM · network-resilience · both
**Источники:** queue M2 (CONFIRMED-UPGRADED: цикл хуже описанного, severity оставлена MEDIUM из-за редкого триггера; репро M2 воспроизведён) = server L3 (CONFIRMED).

**Описание.** Ни `sendMaybeCompressed` (`RemoteClient.kt:448-455`), ни `WireCompression` (`:52-73`, `:83-91`), ни compose bar (`SessionDetailStore.kt:1441-1510`) не ограничивают размер исходящего сообщения; `MAX_ATTACHMENT_BYTES` относится только к upload. tungstenite отвергает inbound >1 MiB (`sawe/crates/remote_control/src/listener.rs:696-697`) с read error → `handle_conn` возвращает `Err` (`:1008-1010`) → сокет сброшен без Close-кадра и без JSON-RPC-ошибки (`parse_request` не достигается). На fast-path это одноразовый отказ (N-01). На пути очереди элемент восстанавливается (`QueueController.kt:321-337`), `QueueChanged` → повторный flush на следующем `Connected` (`RemoteClient.kt:868`). **Коррекция верификатора:** `lifecycleLoop` ставит `attempt = 0` на каждом `Connected` и `attempt = 1` после транзиентного обрыва (`RemoteClient.kt:~700-720`), поэтому используется `backoff.nextDelayMs(1)` = 1 с на **каждом** цикле — лестница не растёт: ~1-секундный цикл connect→TLS→HMAC→1 MiB send→kill до 24 ч. Каждый reconnect заодно роняет все in-flight вызовы (refresh сессии, heartbeat).

**Сценарий отказа.** Пользователь вставляет многомегабайтный лог в поле при `Reconnecting` (сжатый кадр всё ещё >1 MiB) и жмёт Send → приложение фактически неработоспособно против этого сервера ~24 ч, баннер моргает «Reconnecting… attempt 1».

**Верификация.** Репро `M2` (компрессия согласована, `queueCall` 3 MiB hex-строки в Connected): `M2 fast-path frame sizes: binary=1791913 text=129` — кадр 1.79 MiB передан транспорту.

**Исправление (both).** Client: cap (например 900 KiB после компрессии) в `sendMaybeCompressed`/`sendMessageBlocks` с user-facing ошибкой; в `dispatchQueuedItems` считать последовательные отказы per-item и bounce через `onMessageExpired` после N попыток вместо retry весь TTL. Server: на `Error::Capacity` слать `Close(1009, "message too big")` перед возвратом (для кадра, который tungstenite отказывается читать, JSON-RPC-ответ невозможен).

### 4.2 Жизненный цикл соединения (первое подключение, reconnect, zombie, смена сети, foreground/background)

#### N-09 · HIGH · network-resilience / bug (stuck UI) · client
**Источники:** transport H1 (CONFIRMED) = connmgr H1 (CONFIRMED, «хуже описанного») = ui H2 (CONFIRMED).

**Описание.** `RemoteClient.connect()` завершает gate `firstConnect` исключением на *первой* транзиентной неудаче (`core/src/main/kotlin/ru/sipaha/sawe/core/RemoteClient.kt:732-740`), при этом `while (!closing)` (`:687`) продолжает reconnect-цикл в фоне. `ConnectionManager.switchToServerLocked` присваивает `client = newClient` (`app/src/main/kotlin/ru/sipaha/sawe/app/vm/ConnectionManager.kt:363`) и обрабатывает неудачу как `.onFailure { _state = Disconnected(error); return }` (`:368-373`) — `onFailure` inline, это **нелокальный return** из функции до `startObservingConnectionState` (достигается только из `capabilities`-веток `:418`/`:423`). Последствия: `_rawConnectionState` навсегда `Disconnected` (пишется только в observer, `:549-552`) — баннер «Нет связи» через 15 с, строка Settings и pill Servers врут; heartbeat не запущен (`:594`); `onReconnected` не срабатывает → нет `restartNotificationsObserverAndAwait`, `workspaceStore.refresh()`, `uploadManager.resumeAll()`; `UiState.Connected` пишется только в `:417`, так что `AppNavGraph` не уходит с `pairing`. Все действия пользователя — no-op: `switchToServer(sameId)` (`:312`) и `switchToServerLocked(force=false)` (`:328`) выходят при `client != null`; `addServer` с тем же fingerprint переиспользует id (`:230-231`) → тот же guard; `probeLivenessNow` (`:163-177`) видит `!Connected` → `wakeReconnect()` и `return false`. Классификация: `UnknownHost`/`ConnectException`/`SocketTimeout`/`SocketException` → `Unreachable` (`ConnectFailure.kt:174-191`), `HandshakeTimeout` тоже транзиентный — т.е. **каждый** офлайн-холодный старт попадает в эту ветку. Подслучай `capabilities` RPC упал (`:420-424`): observer запущен, данные идут, но `UiState` прибит к `Disconnected` и guards `:312`/`:328` блокируют re-switch. Терминальный подслучай (`AuthRejected` → `FailedTerminal`): `client` остаётся non-null, re-scan нового QR — no-op (см. N-18).

**Сценарий отказа.** Один сервер, холодный старт в лифте / при спящем ноутбуке: `startDestination = "workspace"`; первая попытка `Unreachable` → `UiState.Disconnected` → `popBackStack("pairing")` no-op; спустя 10 с клиент подключается в фоне (сервер `listener.rs:540-558` принимает и видит телефон онлайн), но workspace остаётся `Loading` навсегда (`WorkspaceStore.kt:109`; `refresh()` вызывается только из foreground-probe `MainViewModel.kt:297` — который возвращается раньше, из `onReconnected` `:364` — не срабатывает, и из `refreshWorkspace()` `:545` — без вызывающих в UI; pull-to-refresh нет), баннер «Нет связи». Первая пара: пользователь застрял на `QrPairingScreen` со снэкбаром «connect timeout» при живом сокете за ним; re-scan ничего не делает.

**Верификация.** Трассировка тремя верификаторами; connmgr-верификатор ужесточил: в ветке отказа `connect()` workspace не загружается вообще (не «баннер врёт, pull-to-refresh работает»). Единственные выходы в приложении — Edit server с изменённым транспортным полем (`editServer` → `force = true`, `:299-303`) или remove + re-pair; иначе force-stop.

**Исправление (client).** Вызывать `startObservingConnectionState(newClient)` сразу после `client = newClient`, до `connect()`; выводить `UiState.Connected`/`Disconnected` из наблюдаемого `ConnectionState`, а schema-gate (`capabilities`) запускать на каждом rising `Connected` edge, а не один раз; в guards учитывать состояние клиента (`rawConnectionState !is FailedTerminal`) и использовать `force = true` из `addServer` при изменении pairing URL.

#### N-10 · HIGH · bug (stuck UI) · client
**Источники:** ui H1 (CONFIRMED; симптомы уточнены) + ui L5 (LOW, симптом этой же находки).

**Описание.** `MainActivity.onCreate` вызывает `viewModel.coldStartLandingRoute()` — единственный автоматический триггер `switchToServer` (`app/src/main/kotlin/ru/sipaha/sawe/app/MainActivity.kt:31-37`; `ConnectionManager.kt:524-533`, `:531`) — **только при `savedInstanceState == null`**. После убийства процесса в фоне activity пересоздаётся с непустым bundle, но `MainViewModel` новый: `ConnectionManager.init` (`:125-137`) лишь гидрирует `_activeServerId`/`_pairedServers`, `_state = Disconnected()` (`:86`), `client == null`. `rememberNavController()` восстанавливает back stack (`[workspace(, workspace/sessions/{id})]`), `AppNavGraph` на `Disconnected` делает `popBackStack("pairing")` (`AppNavGraph.kt:128-131`) — no-op, т.к. `pairing` не в стеке. `onForegroundResume` (`MainViewModel.kt:264-266`) и `probeLivenessNow` (`ConnectionManager.kt:163-164`) возвращаются при `client == null`; `ForegroundEdgeDetector.hasReportedFirstStart` (`ForegroundEventBus.kt:96-104`) к тому же глушит первый `0→1` edge нового процесса. Никакого `SavedStateHandle`/`BackHandler`/`finish()` в `app/src/main` нет.

**Сценарий отказа (уточнённый).** Телефон на LTE, пользователь читал чат, Home; через 20 мин ОС убила процесс; тап по иконке → workspace: `WorkspaceUiState.Loading` навсегда; восстановленный маршрут чата: не спиннер, а `UiData.Error("No active server connection. Pick or pair a server first.")` (`SessionDetailStore.kt:543-548`, `notConnectedMessage()` `ConnectionManager.kt:207-208`) — вводящая в заблуждение подсказка «pair a server» при спаренном активном сервере (ui L5), compose bar отключён (`SessionDetailScreen.kt:511-512`); через 15 с баннер «Нет связи». Airplane-toggle ничего не меняет — `RemoteClient` не существует. Один сервер: Settings предлагает только «Edit address» (rebind лишь при изменении host/port/secret/fingerprint, `ConnectionManager.kt:281-304`) и «Forget». Несколько серверов: Back до `servers` и тап по активной строке восстанавливает (`ServersListScreen.kt:128` → guard `:312` проходит при `client == null`), но об этом ничего не говорит.

**Верификация.** Трассировка; исходная ссылка ревьюера `MainActivity.kt:381-388` неверна (файл 45 строк), код на `:31-37` как описано.

**Исправление (client).** Запускать авто-connect из конструкции ViewModel (например, в `ConnectionManager.init` после гидрации под guard `client == null`), а `coldStartLandingRoute` оставить чистым вычислением маршрута; ветка `savedInstanceState == null` должна решать только стартовый маршрут. Формулировку `Disconnected` при непустом `pairedServers` заменить на «Not connected — reconnecting…».

#### N-11 · HIGH · bug (stuck UI, wrong message) · client (+server-side observation)
**Источники:** connmgr H2 (CONFIRMED) + connmgr V-CM-2 (LOW, server, включён сюда как серверная часть той же проблемы).

**Описание.** `.onSuccess` вызова `capabilities` (`ConnectionManager.kt:374-389`) не проверяет `resp.error`/`resp.toolError()`; `call()` не бросает на error-envelope (`RemoteClient.kt:396-400`). `resp.structuredContent()` (`core/.../JsonRpc.kt:72-75`) возвращает null, когда `result` не `JsonObject` — т.е. на каждом error-envelope и на tool-`isError` без `structuredContent` → `?: CapabilitiesDto()` с `wireSchemaVersion = 0` (`RemoteDtos.kt:119-122`) → `isServerTooOld(0)` = `0 < 6` → true (`:72`, `:100-101`) → `tearDownConnection()` (`client = null`, `:500-501`) + `UiState.IncompatibleServer` (`:409-415`). Гейт заменяет NavHost (`AppNavGraph.kt:144-148`), `IncompatibleServerScreen` без callbacks (`:306-`), `onForegroundResume` возвращается при `activeClient() == null`. Сервер: на первом запросе соединения лениво `dispatcher.open_connection()`; отказ → `JsonRpcResponse::error(-32603, "opening local MCP proxy: …")` и `continue` при живом WS (`sawe/crates/remote_control/src/listener.rs:1131-1151`; комментарий `:966-970` — намеренно, «a flapping editor restart shouldn't kick paired phones»). ENOENT сокета → мгновенный отказ (5 с `CONNECT_TIMEOUT` только при существующем пути без accept); также `CALL_TIMEOUT` 30 с (`proxy.rs:152-166` → `-32603`, `dispatch.rs:215-217`) и любой upstream MCP error (`dispatch.rs:191-205`). Настоящий сервер никогда не шлёт 0 (`editor_mcp/src/tools/capabilities.rs:115`: 6). **Серверная сторона (V-CM-2):** `editor_mcp::start_server(cx).log_err()` (`sawe/crates/zed/src/main.rs:1704`) — при провале (stale lock/socket, ср. `editor_mcp/tests/startup_failure_lock_e2e_test.rs`) listener продолжает принимать телефоны и отвечать `-32603` на всё; отдельного признака «editor up, MCP down» у клиента нет.

**Сценарий отказа.** Редактор (пере)запускается: listener уже аутентифицирует, MCP-сокет ещё не привязан (`remote_control::init` в `main.rs:1363`, `editor_mcp::start_server` в `:1704`) → телефон, открытый в этот момент, показывает постоянный экран «This server is too old for this app version. Please update the editor» вместо retry через пару секунд. При провале `start_server` — каждый телефон получает этот экран.

**Верификация.** Трассировка на обеих сторонах; описание точное, добавлено, что `-32603` покрывает и `CALL_TIMEOUT`, и upstream-ошибки.

**Исправление (client; server — по желанию).** Гейтить только по успешно декодированному `CapabilitiesDto`; `resp.error != null || toolError() != null || structuredContent() == null` трактовать как транзиентный отказ пробы (та же обработка, что `.onFailure`, retry на следующем `Connected` edge). Плюс Retry на `IncompatibleServer` (N-20). Server: отвечать `remote.editor.capabilities` из статической in-process структуры без MCP-прокси либо возвращать отдельный код / `data.kind = "mcp_unavailable"`, который клиент маппит на свой баннер.

#### N-12 · MEDIUM · bug (stuck UI) · client
**Источники:** connmgr V-CM-1 (дополнительная находка верификатора).

**Описание.** `WorkspaceStore._state` стартует `Loading` (`app/src/main/kotlin/ru/sipaha/sawe/app/vm/WorkspaceStore.kt:109`). `workspaceStore.refresh()` вызывается ровно из трёх мест: успех foreground-probe (`MainViewModel.kt:297`), `onReconnected` (`:364`) и `refreshWorkspace()` (`:545`), у которого нет вызывающих в `app/.../ui/`; `WorkspaceScreen.kt` вызывает только `closeSessionTab/closeSolution/createSolution/deleteSession/deleteSolution` — ни refresh, ни retry, ни pull-to-refresh. Если observer не подключён (N-09) или первый `refresh()` упал (окно `-32603` из N-11 на свежем соединении, 30-секундный таймаут `call` на медленном линке), ничего не перезапускает загрузку до следующего `Connected` edge или foreground edge с живым сокетом.

**Сценарий отказа.** Холодный старт на LTE, handshake успешен, первый `workspace.snapshot` RPC истекает через 30 с (медленный линк, занятый сервер) → `Error`/`Loading` до тех пор, пока пользователь не свернёт и не развернёт приложение (и проба пройдёт).

**Верификация.** Грепом подтверждено отсутствие call sites `refreshWorkspace()` в UI.

**Исправление (client).** Pull-to-refresh / retry на `WorkspaceScreen`, привязанный к `refreshWorkspace()`; вызывать `workspaceStore.refresh()` из `openSession`/`startObservingSessions`, пока mirror в `Loading`.

#### N-13 · MEDIUM · network-resilience · client
**Источники:** transport M2 (CONFIRMED) = server L4 (CONFIRMED).

**Описание.** `withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS)` (`RemoteClient.kt:789`, 10 с — `ConnectFailure.kt:137`) стартует сразу после `transportFactory.connect` (`:770`), а `client.newWebSocket` возвращается сразу после `dispatcher.enqueue` (OkHttp 5.3.0 `RealWebSocket.kt:169`, `RealCall.kt:184-188`), так что 10 с покрывают DNS + TCP (сам по себе 10 с по `OkHttpClient.kt:612`) + TLS 1.3 + HTTP 101 + challenge + response/welcome ≈ 4–4.5 RTT плюс accept-rate sleep сервера до 200 мс (`listener.rs:372-389`). Сервер даёт 10 с **на каждую стадию** (`listener.rs:659`, `:698`, `:738`). KDoc `ConnectFailure.kt:24-27` («server didn't send the nonce») устарел. `RemoteTransportListener.onOpen` существует, но `HandshakeListener` его не использует (`RemoteTransport.kt:34`).

**Сценарий отказа.** Роуминг / EDGE / перегруженный Wi-Fi с RTT 2.5–4 с → каждая попытка истекает за 10 с; `Reconnecting` навсегда с вводящим в заблуждение «Server didn't complete the handshake within 10s. The remote endpoint may not be spk-editor.» при исправном сервере (в его логах — успешные auth от просочившихся попыток, N-21).

**Верификация.** Трассировка по коду клиента, OkHttp-исходникам и серверу.

**Исправление (client).** Запускать таймер challenge в `onOpen()` (~10 с) и ограничивать pre-open фазу собственными таймаутами OkHttp (`connectTimeout`/`readTimeout`/`callTimeout` 20–30 с — OkHttp снимает `callTimeout` после upgrade для WebSocket); либо просто поднять `HANDSHAKE_TIMEOUT_MS` до 25–30 с и поправить KDoc. Закрывает и большую часть N-21.

#### N-14 · MEDIUM · concurrency / network-resilience · client
**Источники:** transport M3 (CONFIRMED; наблюдаемое поведение скорректировано; репро воспроизведён).

**Описание.** `runOneAttempt` публикует `transport = tx` (`RemoteClient.kt:799`) и затем ждёт `onConnected()` → `call("remote.editor.subscribe")` с таймаутом 30 с (`:856-866`, `:396-400`). `failPendingOnDisconnect` вызывается только из `awaitDisconnect` (`:830`, `:835`), который в это время не выполняется, поэтому `onFailure`/`onClosed` с OkHttp-потока (`:1024-1026`, `:1038-1040`, не stale) просто лежат в `events`. Окно = один RTT (welcome → subscribe reply) — секунды на целевых линках.

**Сценарий отказа (скорректированный).** Моргающий Wi-Fi: handshake успешен, линк умирает через 200 мс. Вместо reconnect за ~1 с: 30 с в состоянии `Connecting` с зависшими вызовами приложения, потом `TimeoutCancellationException`, и только тогда `Reconnecting`. **Коррекция:** в течение этих 30 с состояние — `Connecting`, а не ложный `Connected`; запись `Connected` в `:807` немедленно перекрывается `awaitDisconnect`, потребляющим уже лежащее событие без suspension, так что коллектор StateFlow `Connected` не видит (`connectedAfterDrop=[]`); «баннер прячется + цикл refresh» не происходит. Побочный эффект: `lastConnectedAtMs` бампится для мёртвого сокета (перевзводит 12-секундный grace `forceReconnect`).

**Верификация.** Репро (AuditRepro_TransportTest, subscribe на conn1 → drop → reconnect → handshake → fail transport до ответа replay): `stateWhileAwaitingSubscribe=Connecting`, `stateRightAfterDrop=Connecting`, `stateAfter1s=Connecting`, вызов приложения жив через 1 с, падает на +30 с с `TimeoutCancellationException`, первый `Reconnecting` на t0+30000. Воспроизведено.

**Исправление (client).** `HandshakeListener.onFailure/onClosed` (Established, не stale) вызывать `failPendingOnDisconnect` напрямую в дополнение к постингу события (он уже thread-safe); replay-`call` дать короткий таймаут.

#### N-15 · MEDIUM · network-resilience · client
**Источники:** transport M6 (CONFIRMED) = connmgr M4 (CONFIRMED).

**Описание.** `probeLivenessNow` (`ConnectionManager.kt:163-195`): один вызов `capabilities` под `withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS = 8_000)` (`:681`), `ok = pingResp != null && pingResp.error == null`, иначе `forceReconnect` (`:192`); grace защищает только сокеты моложе 12 с (`RemoteClient.kt:322-328`, `:1165`). Периодический watchdog требует двух промахов (`:660-666`), проба — одного. Вызывающие: `MainViewModel.onForegroundResume` (`:283`) на каждый foreground edge и `SessionDetailStore.openSession` (`:682-686`) на каждое открытие чата. Foreground edge срабатывает и при возврате из системного file picker / share sheet (`ForegroundEventBus.kt:102-112`). Каскад: `forceReconnect` → `TransportClosed` → `onConnectionInterrupted` (`pauseAll` всех загрузок) → `Reconnecting` → handshake (ограничен тем же 10-секундным бюджетом N-13) → `onReconnected` → `resumeSession` + `restartNotificationsObserverAndAwait` + `workspaceStore.refresh()` + `uploadManager.resumeAll()`. JSON-RPC error-envelope (`-32603`, прокси лежит) считается «мёртвым» → reconnect на каждом unlock/open при здоровом транспорте.

**Сценарий отказа.** Пользователь на перегруженном линке (RTT 3–5 с, один retransmit добавляет 1–3 с) разблокирует телефон: ответ пробы приходит на 9-й секунде → сокет снесён на 8-й, баннер, загрузки в Paused, полный refetch workspace; при неудачном handshake — потеря рабочего-но-медленного соединения до улучшения линка. Повторяется на каждом unlock и каждом выборе вложения.

**Верификация.** Трассировка; цепочка в N-13 реальна.

**Исправление (client).** Две попытки (или таймаут ≥15 с) как у watchdog'а; `forceReconnect` только на транспортных отказах (`NotConnectedException`, таймаут, «connection closed»), error-envelope считать «wire alive»; пропускать пробу, если последний успешный RPC/pong моложе нескольких секунд; предпочитать pong-часы OkHttp.

#### N-16 · MEDIUM · network-resilience · client
**Источники:** transport M7 (CONFIRMED) = connmgr M3 (CONFIRMED).

**Описание.** `grep -rn "ConnectivityManager|NetworkCallback|ACCESS_NETWORK_STATE|registerDefaultNetworkCallback"` по `app/` и `core/` — 0 совпадений; манифест объявляет только `INTERNET` и `CAMERA` (`app/src/main/AndroidManifest.xml:5-6`). `wakeReconnect` действует только в `Reconnecting` (`RemoteClient.kt:288-292`) и вызывается только с foreground edge (`MainViewModel.kt:271`) и из not-Connected ветки `probeLivenessNow` (`ConnectionManager.kt:175`); `forceReconnect` — только в `Connected` + grace (`:322-328`). Обнаружение мёртвого сокета — pong-timeout OkHttp (30–60 с) или watchdog (≤ ~76 с). Backoff 1/2/4/8/16/30 с без jitter (`Backoff.kt:31-38`), счётчик сбрасывается только на wake или успешном connect (`RemoteClient.kt:691-709`): после ≥6 неудач первая попытка после возврата сети запаздывает до 30 с.

**Сценарий отказа.** Выход из зоны Wi-Fi с доступным LTE: Wi-Fi «подключён» без throughput ~20–30 с, затем переключение; итого 40–60 с замороженного чата с крутящимися отправками против ~2 с с `NetworkCallback.onAvailable/onLost` → `forceReconnect`. После 3 мин под землёй попытки прибиты к 30 с — пользователь смотрит «Переподключение… (попытка 8)» до 30 с после возвращения покрытия. Captive-portal Wi-Fi в кафе: WS остаётся на портальной сети без интернета, состояние `Connected`, чат «замер» до pong-timeout.

**Верификация.** Грепом подтверждено на обеих итерациях.

**Исправление (client).** `ACCESS_NETWORK_STATE` + `registerDefaultNetworkCallback`; на `onAvailable`/`onCapabilitiesChanged(VALIDATED)` → `wakeReconnect()`, при смене default `Network` в `Connected` → `forceReconnect("network changed")` в обход 12-секундного grace; на `onLost` без замены — пауза retry-цикла до `onAvailable` (закрывает часть N-19). Опционально привязывать socket factory OkHttpClient к текущей default `Network`.

#### N-17 · MEDIUM · network-resilience · client
**Источники:** transport M8 (CONFIRMED; класс триггеров сужен).

**Описание.** Trust manager бросает `CertificateException("leaf certificate fingerprint mismatch (pinning failure)")` (`core/src/main/kotlin/ru/sipaha/sawe/core/FingerprintPinningTrustManager.kt:71-80`); `ConnectFailure.classify` по подстроке «fingerprint» (`ConnectFailure.kt:151-155`) или классу `.CertificateException` (`:157-161`) → `TlsPinMismatch`, `isRetryable = false` (`:65`) → `TerminalFailure` (`RemoteClient.kt:1022-1023`) → `FailedTerminal`, цикл `return` (`:725-730`). Из `FailedTerminal` нет retry; приложение не может перепривязать тот же сервер (`ConnectionManager.kt:312`, `:328`; `addServer` переиспользует id). Восстановление = `removeServer` + re-scan или переключение на другой сервер и обратно. **Коррекция:** `ConnectionSpec` — только TLS 1.3 (`OkHttpRemoteTransport.kt:98-105`), так что перехватчик с TLS 1.2 даёт `TlsNegotiationFailed` (retryable), редирект на plain HTTP — тоже; до pin-check доходит только TLS-1.3-способный перехватчик на кастомном порту (корпоративная прозрачная инспекция, некоторые DNAT-all порталы). Безопасность не страдает от retry: при провале TLS-handshake application data не посылаются, HMAC — только после `welcome`-пути по установленной TLS-сессии. Сервер регенерирует сертификат только при нечитаемых файлах (`cert.rs:41-63`), настоящая смена pin редка.

**Сценарий отказа.** Гостиничный Wi-Fi с DNAT-all порталом на кастомном порту: старт → TLS к порталу → pin mismatch → баннер «Re-pair…»; пользователь логинится в портал; приложение остаётся в `FailedTerminal` до force-stop / remove+re-pair.

**Верификация.** Трассировка; severity MEDIUM сохранена из-за «stuck until remove+re-pair».

**Исправление (client).** Держать `TlsPinMismatch` терминальным для *метки/баннера* (пользователь должен видеть, что peer не тот), но продолжать медленный retry (например 60 с и на `wakeReconnect`) без ослабления pin; при первом успешном pinned-handshake — в норму. Независимо: разрешить re-bind активного сервера из `FailedTerminal`.

#### N-18 · MEDIUM · network-resilience (UI truthfulness / recovery) · client
**Источники:** ui M2 (CONFIRMED).

**Описание.** `ConnectionBannerText.kt:21-22` отображает и `Disconnected`, и `FailedTerminal` как «Нет связи». `ConnectionStatusBanner.kt:94` имеет `onRePair: (() -> Unit)? = null`, кликабелен только при `onRePair != null && state is FailedTerminal` (`:174-186`); грепом — **ноль** call sites `onRePair`. Settings пишет «Re-pair required (reason)» (`SettingsScreen.kt:318-319`), pill Servers — «Re-pair required» (`ServersListScreen.kt:236-237`), но действия только Forget / Switch. Терминальное состояние липкое (`RemoteClient.kt:714-718`, `:725-729`; `AuthRejected.isRetryable = false`, `ConnectFailure.kt:77`), observer лишь ставит баннер (`ConnectionManager.kt:556-557`), не обнуляя `client`. Re-scan: `addServer` (`:225-246`) находит `existing` по `fingerprintHex`, апсертит новый URL (секрет сохранён), затем `switchToServerLocked(server.id, force = false)` → `:328` return, старый `RemoteClient` со старым секретом остаётся. Сертификат персистентен (`sawe/crates/remote_control/src/cert.rs:2-4`), так что регенерированный секрет всегда идёт по этому merge-пути. Сервер (`auth.rs:56-91`) пробует секреты всех авторизованных клиентов — ротация/удаление даёт `AuthRejected`.

**Сценарий отказа.** На десктопе перегенерировали pairing; на телефоне часами «Нет связи» (пользователь винит сеть); скан нового QR → ничего; только «Forget» + re-pair с потерей локального состояния (черновики, очередь, кэш истории).

**Верификация.** Трассировка; отдельной функции «regenerate pairing» в десктопных crates грепом не найдено, сценарий верен для любого изменения allow-list, меняющего секрет телефона.

**Исправление (client).** Отдельный текст баннера для `FailedTerminal` + tap-to-re-pair (передавать `onRePair` из workspace/chat); в `addServer` — `switchToServerLocked(id, force = true)` при изменении сохранённого pairing URL или состоянии `FailedTerminal`.

#### N-19 · MEDIUM · network-efficiency (батарея / трафик) · client
**Источники:** connmgr M2 (CONFIRMED).

**Описание.** `heartbeatJob` на `viewModelScope` (`ConnectionManager.kt:611-614`, `MainViewModel.kt:155`) — безусловный `while (true) { delay(30 s) … }`; `viewModelScope` переживает `onStop`. Reconnect-цикл (`RemoteClient.kt:687-742`) повторяет вечно с cap 30 с без jitter. `ForegroundEventBus.kt` эмитит только `0→1` (`:102-112`) — background edge нет, потребителя, который закрыл бы сокет или приостановил цикл, нет. Foreground-service в манифесте нет (правильно), но и политики для фонового окна нет. KDoc `ForegroundEventBus` («socket dies in background → reconnect on foreground») реализован наполовину.

**Сценарий отказа.** Телефон на столе, экран выключен, не на зарядке, приложение в фоне: до глубокого Doze (30–60 мин) — попытка reconnect (TLS handshake) каждые 30 с, если радио сбросило сокет, иначе 2× keepalive-трафик; на зарядке на Wi-Fi ночью — живой сокет + 2 keepalive/30 с всю ночь ради UI, на который никто не смотрит; на десктопе — churn «kicking previous connection» каждые 30 с. Сервер churn не банит (`listener.rs:649-658`), но каждый фоновый reconnect стоит TLS-handshake + `kick_existing_for_client`.

**Верификация.** Трассировка.

**Исправление (client).** Эмитить background edge (`1→0`) из `ForegroundEdgeDetector`; на нём отменять heartbeat и после grace (~60 с) закрывать сокет / парковать цикл до следующего foreground edge через `wakeReconnect`/probe — кроме случая идущей загрузки.

#### N-20 · LOW · network-resilience (hardening) · client
**Источники:** connmgr L2 (CONFIRMED).

**Описание.** Сравнения версий корректны (точное совпадение с `SUPPORTED_WIRE_SCHEMA_VERSION = 6`, `RemoteDtos.kt:72,83-84,100-101`; сервер `capabilities.rs:115`), но после гейта (`ConnectionManager.kt:396-415` → `tearDownConnection()` → `client = null`) нет ни клиента, ни observer, ни кнопки (`AppNavGraph.kt:133-148`, `:306-`); `MainViewModel.kt:265` и `probeLivenessNow` возвращаются при `client == null`.

**Сценарий отказа.** Пользователь видит «Please update the editor», обновляет и перезапускает десктоп — телефон гейтится до убийства из Recents. Становится важным вместе с N-11 (ложные срабатывания требуют выхода).

**Исправление (client).** Кнопка Retry → `switchToServer(activeId)` (работает: `client == null` в `:312`) и авто-retry на foreground edge при `state is IncompatibleServer`.

#### N-21 · LOW · leak / network-resilience · client
**Источники:** transport M1 (CONFIRMED-DOWNGRADED → LOW: утечка потока+сокета реальна, «stall dispatcher'а» отвергнут).

**Описание.** По истечении `withTimeoutOrNull` `runOneAttempt` вызывает только `tx.close()` (`RemoteClient.kt:810`, `:817`) → `WebSocket.close(1000, …)` (`OkHttpRemoteTransport.kt:119-121`); `RemoteTransport` не имеет `cancel()` (`RemoteTransport.kt:23-27`). В OkHttp 5.3.0 `RealWebSocket.close()` (`:472-495`) лишь ставит `enqueuedClose` и `runWriter()`, который no-op пока `writerTask == null` (`:498-505`) — а он создаётся только после HTTP 101 (`:287`); прервать connect может только `RealWebSocket.cancel()` (`:143-145` → `RealCall.cancel()`). `readTimeout(0)` (`OkHttpRemoteTransport.kt:111`) применяется как `soTimeout` (`ConnectPlan.kt:274`), так что TLS-handshake и чтение upgrade-ответа не ограничены; ограничен только TCP connect (10 с по умолчанию). **Коррекция:** `Dispatcher.promoteAndExecute` считает per-host лимит только для не-WebSocket вызовов (`Dispatcher.kt:189-193`; WS `AsyncCall` держит свой `AtomicInteger(0)`, `RealCall.kt:526`), поэтому сценарий «6-я попытка сидит в `readyAsyncCalls`» невозможен; WS-подключения ограничены только глобальным `maxRequests = 64`. Также stale-попытка, открывшаяся позже, **не** держит аутентифицированный сокет: `handshakeTransportRef` обнулён до возврата таймаута (`:811`/`:818`), stale-listener не отвечает на challenge (`:970`), сервер сбрасывает через 10 с (`listener.rs:738-742`). Только попытка, истёкшая после отправки HMAC (AwaitingVerdict), аутентифицирована на сервере; её Close-кадр пишется при старте writer'а, OkHttp force-cancel через 60 с (`:549-551`). Остаточный вред: один заблокированный поток executor'а + один сокет на каждый black-holed TLS/upgrade до момента, когда ОС убьёт сокет.

**Сценарий отказа.** LTE handoff сразу после TCP connect (ClientHello black-holed) несколько раз подряд → столько же подвисших потоков/сокетов до TCP retransmit give-up; функционального stall'а нет.

**Верификация.** По исходникам OkHttp 5.3.0 из gradle cache.

**Исправление (client).** Добавить `cancel()` в `RemoteTransport` → `WebSocket.cancel()` и вызывать на путях таймаута/исключения; и/или конечный `readTimeout`/`callTimeout` (OkHttp снимает callTimeout после upgrade, `RealCall.timeoutEarlyExit`) — ограничивает TLS+upgrade без влияния на steady state. См. N-13.

#### N-22 · LOW · network-resilience / efficiency · client
**Источники:** transport M4 (CONFIRMED-DOWNGRADED → LOW; репро-тест прошёл = задокументированное поведение).

**Описание.** `attempt = 0` в момент `Connected` (`RemoteClient.kt:709`), `attempt = 1` после транзиентного обрыва (`:723`); `Backoff.kt:6-7` документирует именно это. Нет условия «соединение прожило ≥ N с», поэтому peer, роняющий соединения сразу после auth, превращает клиент в 1 Гц цикл TCP+TLS 1.3+HMAC; `FORCE_RECONNECT_GRACE_MS` (`:1165`) защищает только от собственных проб. Единственный конкретный триггер — два устройства с одним client name (сервер `listener.rs:540-558`, `:810` вытесняет старый слот на каждом свежем auth): cap 30 с лишь замедлит флаппинг, а не вылечит (нужен серверный close-код «already connected elsewhere» или разные имена). Один флапающий телефон остаётся под accept-limiter (5/с burst 10, `listener.rs:72-77`). Вред сегодня — батарея/CPU при misconfiguration, не stuck state. Та же механика делает N-08 1-секундным циклом.

**Верификация.** Репро с `BackoffStrategy.Default`: 4× (server close 1001 → reconnect → handshake) — `Reconnecting(attempt=1, 1000 ms)` каждый раз, 5 handshake за 4.0 с virtual; тест прошёл.

**Исправление (client, hardening).** Сбрасывать `attempt` только если соединение прожило ≥ N с (сравнивать `lastConnectedAtMs` в момент обрыва), иначе продолжать лестницу; jitter опционален.

#### N-23 · LOW · concurrency / leak · client
**Источники:** transport L2 (CONFIRMED; репро воспроизведён) + transport L9 (дополнительная, LOW).

**Описание.** `forceReconnect` постит непомеченный `TransportClosed` (`RemoteClient.kt:336-338`); `awaitDisconnect` потребляет любой `TransportClosed` безусловно (`:828-832`), фильтр `isStale` есть только в listener (`:923-927`). Если естественный `TransportFailure` уже в очереди (состояние ещё `Connected`) и в этом окне срабатывает watchdog, в очереди два события; второе потребляется *следующим* `awaitDisconnect` на новом сокете → `transport = null`, pending отказаны, ещё один reconnect. При этом (L9) `awaitDisconnect` обнуляет `transport`, но не вызывает `close()` на брошенном транспорте — здоровый сокет остаётся открытым без Close-кадра; на сервере живёт до `kick_existing_for_client` на следующем auth (`listener.rs:540-558`) или 60-секундного idle, а OkHttp-ping держит его живым с клиентской стороны.

**Сценарий отказа.** Zombie-сокет: pong-timeout OkHttp и второй промах heartbeat в одну миллисекунду → после успешного reconnect баннер один раз снова прыгает в «Переподключение…», все in-flight refresh отказаны; один утёкший TLS-сеанс до 60 с. Окно = латентность между `trySend` с OkHttp-потока и dequeue в lifecycle-корутине.

**Верификация.** Репро (`failFromServer` на живом транспорте, `forceReconnect` при обойдённом grace, reconnect, handshake OK): немедленно `Reconnecting(attempt=1, lastFailure=Unreachable("watchdog"))` на t=150 и третий транспорт на t=250 (`transports=3`, `tx2.closed=false`).

**Исправление (client).** Носить идентичность транспорта в `TransportClosed`/`TransportFailure` и отбрасывать несовпадения в `awaitDisconnect`; `toClose = transport; transport = null; toClose?.close()`.

#### N-24 · LOW · network-resilience · client
**Источники:** transport L5 (CONFIRMED; репро воспроизведён).

**Описание.** `wakeReconnect` проверяет `is Reconnecting` (`RemoteClient.kt:289`), которое остаётся true от истечения таймера (`:699`) до `:704`; poke в этом окне буферизуется в CONFLATED-канале (`:175`) и потребляется следующим `withTimeoutOrNull { receive() }` → `attempt = 0`, нулевая задержка. Самоограничено (один лишний handshake, лестница снова с 1 с); accept-limiter сервера поглощает.

**Верификация.** Репро (poke точно на истечении backoff): `stateAtWake=Reconnecting(attempt=1)`; попытка 2 падает; третий транспорт в то же virtual-время (`transportsAtEdge=3`, ожидалось 2, `t=1000`).

**Исправление (client).** Дренировать `wakeRequests` прямо перед `withTimeoutOrNull` или ставить `Connecting` до выхода из гонки.

#### N-25 · LOW · bug · client
**Источники:** transport L7 (CONFIRMED; репро воспроизведён).

**Описание.** Первый текстовый кадр не-challenge → `completeTerminal(ProtocolError(...))` (`RemoteClient.kt:957-964`) при `ProtocolError.isRetryable = true` (`ConnectFailure.kt:108`); binary-во-время-handshake в том же listener использует `completeTransient` (`:933`) — несогласованность. Сервер всегда шлёт challenge первым (`listener.rs:727-734`), так что триггер — только version skew / чужой peer; но баннер «Protocol error: …» без подсказки о re-pair, цикл остановлен.

**Верификация.** Репро: первый кадр `{"type":"challenge","challenge":"0011","v":2}` → `FailedTerminal(ProtocolError(...))`, `connect()` падает с `ConnectException`, retry нет.

**Исправление (client).** Либо транзиентно, либо явный терминальный `VersionSkew` с actionable-сообщением.

#### N-26 · LOW · bug (hardening) · client
**Источники:** transport L6 (CONFIRMED-DOWNGRADED; сужено).

**Описание.** `Adapter.onClosing` → `webSocket.close(code, reason)` (`OkHttpRemoteTransport.kt:72-76`) → `validateCloseCode` (`WebSocketProtocol.kt:136-148`). **Коррекция:** зарезервированные коды peer'а до `onClosing` не доходят — `WebSocketReader` бросает `ProtocolException` раньше (`WebSocketReader.kt:225-228`) → `onFailure`. До echo и throw доходит только **1005** (Close без payload, `WebSocketReader.kt:219`): `close(1005, …)` → `IllegalArgumentException` на reader-потоке → `failWebSocket` → `onFailure` → `Unknown` (транзиентный). 1012–1014 принимаются, будущий «restart» (1012) *не* сработает. Сегодняшний сервер шлёт 1001/1008 с payload (`listener.rs:788-792`, `:1220-1237`); `Close(None)` в `:1034` — только ответ.

**Исправление (client).** Всегда отвечать `close(1000, null)`.

#### N-27 · LOW · concurrency / bug (cosmetic) · client
**Источники:** connmgr L3 (CONFIRMED).

**Описание.** `MainViewModel.removeServer` (`:452-477`) — `viewModelScope.launch(Dispatchers.IO) { … connectionMgr.removeServer(serverId) }`; `ConnectionManager.removeServer` (`:437-457`) выполняет `tearDownConnection()` и `switchToServerLocked(next.id)` в этой IO-корутине, мутируя non-volatile `client` (`:111`) и вызывая `lifecycle.onTearDown/onBeforeSwitch/onClientBound` вне Main вопреки контракту `:41-54`. `connectionMutex` сериализует с другими переходами, вложенные launch'и уходят обратно в `scope` (Main) — конкретной порчи нет. Навигация: `AppNavGraph.kt:214-222` вызывает `viewModel.forgetPairing()` (async, `:479-482`) и сразу читает `viewModel.pairedServers.value.size` → ещё включает сервер → `"servers"` даже для последнего; `ServersListScreen.kt:106-110` показывает пустой «No servers paired»; последующий `UiState.Disconnected()` → `popBackStack("pairing")` no-op при cold-start маршруте `workspace`.

**Сценарий отказа.** Settings → Forget (единственный сервер): пользователь попадает на пустой список вместо QR-экрана; восстановимо через FAB.

**Исправление (client).** Сделать `removeServer` suspend, на IO выполнять только wipes репозиториев, `connectionMgr.removeServer` вызывать на Main; `onForget` ждать завершения (или наблюдать `pairedServers`) перед навигацией.

#### N-28 · LOW · bug (cancellation hygiene) · client
**Источники:** transport L10 (дополнительная) + connmgr L4 (CONFIRMED) + upload L5 (дополнительная). Последствия конкретных сайтов описаны в N-02 (удаление маркера при отмене) и N-40/N-46 (`Failed`/`Paused(sent=uploadId)` перекрывает корректный `Paused`).

**Описание.** `runCatching` вокруг suspend-вызовов проглатывает `CancellationException`: `RemoteClient.connect()` (`RemoteClient.kt:234-265` — отмена вызывающей корутины `switchToServerLocked` под `connectionMutex` превращается в `Result.failure`, `.onFailure` в `ConnectionManager.kt:369-372` пишет `UiState.Disconnected`, корутина продолжает вместо unwind), `:859`, `:816`; `ConnectionManager.kt:374` (`runCatching { newClient.call("remote.editor.capabilities") }` → `UiState.Disconnected(error = "…cancelled…")` + `startObservingConnectionState` на отменённом scope); `SessionListStore.kt:248`, `:267`; `SessionDetailStore.kt:1509`; `UploadManager.kt:467-500` (resume-шаг: отмена из `pauseAll`/`cancel`/`forget` → запись `Paused(uploadId, …)` или `Failed` после того, как caller уже выставил нужное состояние). Heartbeat/probe (`ConnectionManager.kt:181-188`, `:638-645`) и `singleFlightRefresh` делают правильно.

**Сценарий отказа.** Wi-Fi падает, пока upload в `upload_status` после прошлого blip'а → чип прыгает с «37% (paused)» на «100% (paused)» до следующего resume; ViewModel-scope отменён посреди handshake → `_state` перезаписан из корутины, которая должна была остановиться.

**Исправление (client).** `if (t is CancellationException) throw t` во всех перечисленных catch-сайтах, единообразно.

### 4.3 Эффективность трафика и батарея (poll-шторм, whole-entry delta, картинки, list_sessions, keepalive×2, fan-out уведомлений)

#### N-29 · HIGH · network-efficiency / bug · both
**Источники (кластер):** server H1 (CONFIRMED) + sync H1 (CONFIRMED) + sync H2 (CONFIRMED-DOWNGRADED → MEDIUM, порог пересчитан) + server-verify M6 (дополнительная, MEDIUM→HIGH при подтверждении sync H1 — подтверждён). Три механизма перемножаются, поэтому одна запись.

**Описание.**
(a) *Whole-entry re-send (server H1).* `get_session_changes` отбирает все entries выбранного stream с `entry.mod_seq > since_seq`, сортирует и режет по `CHANGED_ENTRIES_PAGE = 10` (`sawe/crates/solution_agent/src/mcp/read.rs:1048,1202-1215`), каждый суммируется `summarize_entry(..., include_full_content = true, include_images, ...)` (`:1244-1251`): весь `markdown` (`dto.rs:838-842`), 200-символьный `preview` безусловно (`:837`), `images[]` (`:843-851`), для tool-call — 500-символьные `args_preview`/`result_preview` (`:1024-1033`) при том что `markdown` уже включает `content_md` (`:741-757`). `EntryUpdated` бампит `change_seq` и переставляет `entry.mod_seq` на **каждое** обновление, не только на throttled emit (`store/acp_event.rs:857-867`; комментарий `:872-874` это подтверждает). Формы append/patch в `read.rs`/`dto.rs` нет. Каждый poll вдобавок везёт `streams[]`, `state`, `pending_bundles` (`read.rs:1282-1320`).
(b) *Cancel-and-rearm (sync H2).* `scheduleDeltaPoll` делает `deltaPollJob?.cancel()` и `launch { delay(200) … call }` (`app/src/main/kotlin/ru/sipaha/sawe/app/vm/SessionDetailStore.kt:1187-1191`); convergence-вариант (`:1238-1242`), tail-resync каждые 4 с (`:707-732`) и все обработчики poke (`:801-833`) отменяют тот же job. `RemoteClient.kt:416-426`: `cont.invokeOnCancellation { pending.remove(id)?.cancel() }` — кадр уже ушёл, cancel на сервер не шлётся, поздний ответ выбрасывается (`:1076-1083`). Сервер исполняет и пишет каждый ответ безусловно (`listener.rs:1131-1158`, `read.rs:1058-1118`) и, будучи последовательным (N-50), задерживает следующий poll на время исполнения+передачи отменённого.
(c) *Недостижимая цель сходимости (sync H1).* Caught-up `current_seq` = `selected_stream.seq` (`read.rs:1211-1221`, `:1147-1156`; `get_session` тоже `:758-766`, `:784`; `model.rs:986-996` — `stream.seq = max(entry.mod_seq)` по этому stream). Payload `agent_session_dirty` = глобальный `session.change_seq` на момент emit (`event_sources.rs:100-119`); `dirty_target_session` (`:74-96`) шлёт его на `SessionStateChanged`, `TitleChanged`, `MessageAppended`, `QueueChanged`, `SubagentsChanged`, `BackgroundAgentsChanged`, `BackgroundShellsChanged`, `ContextReset`. `change_seq` бампится без Main-entry в `store.rs:4533-4544` (`mark_state_changed`), `:4548-4558` (`mark_queue_changed`), `:4562-4571` (`mark_subagents_changed`) — из `mutate_state` на каждом переходе (`:4636-4638`), strip-GC при →Idle (`:4712`), queue push (`:2556`), `store/teammate_reconciler.rs:281`, `model.rs:1152-1157` (`seed_change_seq` = anchor + 3 после рестарта десктопа). Порядок конца хода: `acp_event.rs:287-288` флашит appends (dirty с `change_seq == Main.seq`, достижимо), **затем** `mutate_state(Idle)` → ещё бамп → **последний** dirty каждого хода несёт `Main.seq + 1` (или +2). Клиент: выход `if (sinceSeq >= targetSeq) return@launch` (`SessionDetailStore.kt:1255`); при успешном poll `openSeq = delta.currentSeq` (`:1080-1081`, = `Main.seq`), `failures = 0; backoffMs = CONVERGENCE_MIN_BACKOFF_MS`, `while` крутится **без `delay`** (`:1279-1286`); `CONVERGENCE_MAX_ATTEMPTS = 15` и backoff 150 мс→2 с (`:107-112`) касаются только ветки `delta == null` (`:1264-1270`); 60-секундный safety-net подавлен при живом job'е (`:752`, `:141-145`).

**Реальный worst case (по верификаторам).** Что ревьюер упустил: `startTailResync` (`:707-732`) вызывает `scheduleDeltaPoll` каждые 4 с при Running/Stopping и 3 trailing-тика после Idle, а `scheduleDeltaPoll` отменяет `deltaPollJob` — т.е. заменяет convergence-цикл one-shot poll'ом. Поэтому: *ограниченное окно* (типичный длинный ход) — шторм ≤ 4 с после конца хода; при RTT 60 мс до ~65 RPC / ~100 KB на конец хода, при 1 с RTT ~4 RPC; плюс второе окно в начале хода (`mutate_state(Running)` после user-entry) и по одному на каждое изменение subagent-strip — 2–3 окна по 2–4 с **на каждый ход**. *Неограниченный* цикл: (1) ходы короче ~4 с, где ни один тик не увидел Running (частая ситуация для коротких ответов), (2) любой Idle-time dirty при `trailing == 0` — авто-заголовок, очередь, фоновые shells/agents, subagent-GC, teammate-snapshots, (3) каждый dirty после рестарта десктопа (+3). Тогда poll идёт 1/RTT до закрытия чата: 60 мс ≈ 15 req/s ≈ 1.5 MB/min; 1 с LTE ≈ 1 req/s, радио никогда не спит. Побочно: каждая итерация вызывает `persistCache` → `save` перевзводит 500-мс debounce — дисковый кэш не пишется, пока идёт шторм. Умножение на (a) (server M6): пока Main стримит, `mod_seq` растёт на каждом чанке (десятки мс), так что **каждый** RTT-bounded poll возвращает стримящийся entry целиком: при 60 мс RTT ≈ 15 poll/s × ~10 KB ≈ 50 KB/s, несколько MB на один 20-KB ответ. Умножение на (b): с активным (c) почти всегда есть poll в полёте, так что **каждый** poke отменяет ответ; отменённый ответ передан и выброшен, курсор не сдвинулся, следующий poll шлёт ту же страницу (растущую к 10 целым entries).

**Кадансы (коррекция верификаторов).** Throttle append'ов — перевзводимый trailing-edge debounce (`acp_event.rs:794-830`: каждый `EntryUpdated` `insert`'ит новый 500-мс `Task`, роняя предыдущий; только `first_dirty_at` ≥ 2 с форсирует emit), поэтому при непрерывном стриминге — **один `message_appended`(+`dirty`) на ~2 с на entry**, а не ~2/с; `NewEntry` эмитит немедленно. Отсюда порог starvation для (b) — RTT + server time ≳ ~1.8 с (не ~300 мс): 600-мс LTE не голодает; в целевом диапазоне 1–5 с — голодает. Оценка для ответа 20 KB за 40 с: непрерывный стрим (~0.5 emit/s) ≈ 200 KB raw (10× идеала), bursty (~2/s) ≈ 800 KB raw; после DEFLATE со словарём ≈ 50–200 KB.

**Сценарий отказа.** Агент заканчивает ответ при открытом чате: `message_appended` (+dirty N), `state_changed` Idle (+dirty N+1), `Main.seq = N` → клиент сходится к N и крутится на N+1 часами при открытом чате. Десктоп гоняет Task/Agent-teammate во время стрима Main; телефон на Wi-Fi поллит ~15/s, каждый ответ ≈ текущий размер entry. LTE 100–200 KB/s, длинный ход: 5–20× размера транскрипта, ответы по секундам, очередь в serial loop (N-50), heartbeat истекает → force-reconnect посреди хода → re-download от курсора.

**Верификация.** Трассировка по обеим сторонам тремя верификаторами; чисел ревьюеров скорректированы (см. выше). Тестов на convergence loop в репо нет (только `SafetyNetPollTest`).

**Исправление (both; клиентская половина самодостаточна).** Client: считать `has_more == false` сходимостью (сервер только что удостоверил «ничего новее для этого stream»), retry-цикл оставить только для *упавших* poll'ов, добавить floor-delay между успешными итерациями; single-flight poll с флагом «re-poll requested» — никогда не отменять in-flight `get_session_changes`, отменять только таймер. Server (wire contract, синхронный релиз с версией схемы): класть per-stream `seq` (или все stream seqs) в payload `dirty`; append-форма дельты (`markdown_append`/`(prefix_len, tail)` при `known_len`/`known_hash` от клиента) или пропуск `markdown` при совпадении хэша; не слать `preview` при наличии `markdown`; бампить `mod_seq` только при срабатывании throttled emit (или отдавать `content_len`).

#### N-30 · HIGH · network-efficiency / memory (OOM) · both
**Источники (кластер):** server H2 (CONFIRMED) + sync H3 (CONFIRMED) + ui M4 (CONFIRMED; уточнение про `remember`) + sync M5 (CONFIRMED).

**Описание.** *Путь туда-обратно.* Телефон грузит фото чанками (оригинальные байты, `UploadManager.kt:616-633`; `inSampleSize = 4` в `SessionDetailScreen.kt:3615-3616` — только превью-карточка; лимиты `MAX_PHOTOS_PER_PICK = 4`, `MAX_ATTACHMENT_BYTES = 5 MiB`, `:3526-3527`), шлёт `send_message_blocks` с `spk-upload://`; сервер `resolve_upload_handles` base64-ит весь tmp-файл в `ContentBlock::Image` (`sawe/crates/solution_agent/src/upload.rs:495-507`), `extract_images_for_entry` копирует `image_content.data` verbatim в `EntryImage.data_base64` при `include_images` (`dto.rs:957-975`; downscale/cap нет); append user-entry → `dirty` → телефон скачивает собственное фото обратно. *Клиент просит картинки везде:* `include_images = true` на `get_session` (`SessionDetailStore.kt:970-977`), `loadOlder` (`:1299-1306`), `getSessionChanges(includeImages = true)` по умолчанию (`RemoteClient.kt:568-579`), call sites `:945`, `:1201`, `:1257` не переопределяют; `grep include_images|includeImages` по `app/src/main`, `core/src/main` — ни одного `false`. Сервер: `get_session_changes.include_images` = `default_true` (`read.rs:953-956,969-970`), `get_session` — default **false** (`read.rs:386,446`), `get_session_entry(include_images)` существует (`read.rs:1375`), `RemoteClient.getSessionEntry` (`RemoteClient.kt:535-556`) — **ноль** call sites. Outbound frame cap нет (tungstenite caps только read-side); `DEFAULT_CALL_TIMEOUT_MS = 30_000` (`RemoteClient.kt:1173`). *В памяти:* `EntryImage.dataBase64` — `String` (`RemoteDtos.kt:424-428`; 5 MB фото ≈ 6.7 MB base64 ≈ 13 MB UTF-16) + транзиентный JSON/`JsonElement` при декоде; `_session.entries` никогда не обрезается. *Рендер (ui M4):* `remember(images) { … bitmapPainterFromBase64 }` в UserBubble (`SessionDetailScreen.kt:1479-1481`, eagerly, хотя картинка показывается только по тапу на `[image #N]`) и AssistantMarkdownBody (`:2025-2027`); `Base64.decode` + `BitmapFactory.decodeByteArray` без `Options`/`inSampleSize` (`:2471-2478`) синхронно в composition на Main — 12 MP JPEG ≈ 48 MB ARGB на пузырь; `largeHeap` в манифесте нет. **Коррекция:** `remember(images)` ключуется по `equals`, `List<EntryImage>` (data class) сравнивается по содержимому — пересборка entry без изменения base64 *не* передекодирует (платит многомегабайтным сравнением строк); передекод — при уходе/возврате элемента в viewport. *Кэш (sync M5):* `stripImages` выбрасывает каждую картинку >4 KB перед записью (`SessionHistoryRepository.kt:246-253`, `IMAGE_INLINE_THRESHOLD_BYTES = 4096`); кэшированное окно рендерится как есть (`SessionDetailStore.kt:630-655`); дельта несёт только `mod_seq > since_seq` (`read.rs:1201-1204`) — неизменённый user-entry не пересылается; тап по `[image #N]` молча возвращается при отсутствующей картинке (`SessionDetailScreen.kt:1545`); KDoc `SessionHistoryRepository.kt:43-46`, `:229-235` обещает lazy fetch через `get_session_entry`, которого нет.

**Сценарий отказа.** Сессия с 5 фото по ~1.5 MB впервые открыта на 150 KB/s: `get_session` ≈ 10 MB base64 → ~65 с > 30 с `call`-timeout → `applyFetchFailure` (`SessionDetailStore.kt:952,981`); сервер в это время заблокирован записью (N-50); при reconnect кэш пуст → `fetchFullSession` снова → цикл, ~10 MB на попытку, сессия не открывается. (Верификатор: heartbeat-часть цикла пограничная — второй тик ложится ~на 68-й секунде, у конца записи 10-MB кадра; таймаут `call` и retry — достоверны.) 3 фото в одном сообщении на mid-range телефоне → echo с тремя inline → composition декодирует 3 × ~50 MB → `OutOfMemoryError` или многосотмиллисекундный freeze, повторяющийся при прокрутке. После рестарта пузыри с фото показывают мёртвые `[image #N]`, пока что-то не вызовет полный `get_session` этой страницы.

**Верификация.** Трассировка четырьмя верификаторами, грепом подтверждено отсутствие `include_images=false` и call sites `getSessionEntry`.

**Исправление (both; клиентская часть — основной рычаг).** Client: downscale/recompress фото перед upload (например ≤ 1600 px, JPEG q80 → обычно < 300 KB) — чинит round-trip у источника; `include_images=false` на poll/page путях + lazy per-entry fetch через `get_session_entry(include_images=true)` по тапу/при появлении в viewport, кэш по `(sessionId, entry.index, image.index)`; никогда не перезапрашивать картинку, чей `client_send_id` телефон сам отправил; decode вне Main (`produceState`/`LaunchedEffect` + `Dispatchers.Default`) с `inSampleSize` под viewport, user-bubble — лениво по тапу; в кэше — миниатюры. Server: default `include_images = false` для `get_session_changes` только под gate версии схемы (иначе сломает старые сборки); опционально отдавать картинки через chunked binary path вместо JSON.

#### N-31 · MEDIUM · network-efficiency · both
**Источники (кластер):** transport M5 (CONFIRMED) = connmgr M1 (CONFIRMED) = server L2 (CONFIRMED) + transport L8 (CONFIRMED, server, два pong'а на ping).

**Описание.** `pingInterval(30 s)` (`core/src/main/kotlin/ru/sipaha/sawe/core/OkHttpRemoteTransport.kt:110`); OkHttp 5.3.0 `writePingFrame` (`RealWebSocket.kt:587-615`) роняет сокет `SocketTimeoutException("sent ping but didn't receive pong within …")` при отсутствии pong'а к следующему тику → `classify` → `Unreachable` (`ConnectFailure.kt:174-176`) → `Reconnecting`: мёртвый сокет обнаруживается за 30–60 с. KDoc heartbeat'а (`ConnectionManager.kt:593-600`, «OkHttp's keepalive frames don't see it either») **неверен**. Поверх этого — `remote.editor.capabilities` каждые 30 с (`HEARTBEAT_INTERVAL_MS`, `HEARTBEAT_TIMEOUT_MS = 8_000`, `HEARTBEAT_FAILURE_THRESHOLD = 2`, `:678-690`; цикл `:611-671`), не в фазе с ping'ом; второй промах на +30+8+30+8 = **76 с** → `forceReconnect` (`:666`) — никогда не быстрее OkHttp. Ответ ~1.2–1.6 KB raw (26 `supported_event_kinds`, `binary_path`, `binary_built_at`, `experiments`, text content — `capabilities.rs:26-43,117-162`), через MCP Unix-прокси (`proxy.rs:110-169`). Error-envelope считается промахом (`:646`) → при лежащем прокси (`-32603`) `forceReconnect` каждые ~76 с с баннером «Server stopped responding — reconnecting…», хотя reconnect прокси не чинит (сервер намеренно держит WS живым, `listener.rs:966-970`). Сервер: любой inbound-кадр, включая `Ping`, обновляет idle-таймер и получает pong (`listener.rs:1015-1026`), idle 60 с (`:43`) — одного OkHttp-ping достаточно. `remote.editor.ping`, который рекомендует `listener.rs:40-42`, **не** в `allow_list::translate` (`allow_list.rs:19-74`; только `capabilities/subscribe/unsubscribe/list_subscriptions`) → `-32601` (`dispatch.rs:171-180`); отвечает только тестовый `MinimalDispatcher` (`dispatch.rs:272`). Плюс (L8): `listener.rs:1022-1026` шлёт явный `Message::Pong`, а tungstenite 0.28 уже ставит pong в очередь на каждый Ping (`protocol/mod.rs:672`) — два pong'а на ping; OkHttp лишние игнорирует. Что RPC-heartbeat всё же умеет: заметить процесс сервера, который завис при живом ядре, — но не каждые 30 с.

**Сценарий отказа.** Приложение открыто (или в фоне до Doze) час на LTE: 120 WS ping + 120 `capabilities` RPC (≈ 60–80 KB, ~150–200 KB с учётом ответа) и ~240 пробуждений радио вместо ~120; сервер — 120 proxy-вызовов на idle-телефон; при MCP-outage баннер моргает каждые 76 с.

**Верификация.** По исходникам OkHttp 5.3.0 и tungstenite 0.28.0 (ревьюер server цитировал 0.20.1 из Cargo.lock — версия неверна, поведение то же).

**Исправление (both).** Client: убрать периодический RPC-heartbeat (OkHttp ping + серверный idle уже закрывают zombie-детекцию) или сделать его ≥ 90–120 с / только в foreground / реактивным (после тишины OkHttp); `forceReconnect` оставить для явной foreground-пробы; error-envelope никогда не считать мёртвым проводом. Server: добавить `remote.editor.ping` в allow-list и отвечать прямо в `ProxyConnection::dispatch` без прокси-хопа и GPUI; `Message::Ping(_) => None`.

#### N-32 · MEDIUM · network-efficiency / bug (main-thread I/O) · both
**Источники:** sync M2 (CONFIRMED; уточнение про decode) = server M4 (CONFIRMED).

**Описание.** `SessionListStore.kt:463-488`: `state_changed`/`created`/`closed`/`title_changed` любой сессии → `refreshSessions` (с cached-solution fallback — срабатывает и с экрана чата); `:161-203` — `list_sessions` только с `solution_id` (`:181`), без `count`/`before_last_activity_at_ms` (сервер их поддерживает, `read.rs:170-181`); `onSuccess` → `saveSessions` (перезапись всего `spk_list_cache`) + `sessionHistoryRepository.prune`. `singleFlightRefresh` (`SingleFlightRefresh.kt:37-56`) на `viewModelScope` (Main) вызывает `onSuccess` inline → `prune` на Main: `SessionHistoryRepository.kt:169-195` — `p.all` на `EncryptedSharedPreferences` **расшифровывает каждый ключ и значение файла** (все серверы, все сессии). **Коррекция:** JSON-decode (`:181-183`) только для blob'ов, чей session id **не** в `keepSessionIds` (`continue` в `:178`) — «decrypt all + decode orphans». `singleFlightRefresh` также отменяет in-flight `list_sessions` на новый poke (`:44`) — сервер исполняет всё равно, замена ждёт за ним. Сервер: `hydrate_all_for_solution` на каждый вызов (`read.rs:117-133`) планирует обоих reaper'ов (`store/hydration.rs:1478-1487`); `store.rs:2185-2196` веером шлёт `SessionStateChanged` на каждую сессию агента при probe списка моделей, `mutate_state` на каждом переходе (`:4636-4638`), 21 ссылка в `store/supervisor_engine.rs`; payload `agent_session_state_changed` = только `session_id` (`event_sources.rs:147-153`) — клиент не может патчить строку локально.

**Сценарий отказа.** Solution с 3 активными сессиями и supervisor'ом; пользователь читает один чат на LTE: десятки `list_sessions` в минуту (~13 KB raw / ~3 KB compressed на 30 сессий), каждый — round-trip на 1–3 с RTT в serial loop (N-50) вперемешку с poll'ами транскрипта, плюс main-thread AES-GCM всех кэшированных транскриптов на каждый poke → jank в стримящем чате.

**Верификация.** Трассировка обоих верификаторов.

**Исправление (both).** Client: debounce refresh (1–2 с trailing), пропускать, когда ни один list-surface не наблюдает; refresh только если `session_id` уведомления в текущем списке; передавать `count`; `prune` на `Dispatchers.IO` не чаще раза на открытие solution; не отменять in-flight `list_sessions`, а помечать dirty и переиздавать один раз. Server (additive): класть `state` (и `title`) в payload `state_changed`/`title_changed` — снимает большинство refetch'ей.

#### N-33 · MEDIUM · network-efficiency · client
**Источники:** sync M1 (CONFIRMED).

**Описание.** `openSession` (`SessionDetailStore.kt:542-687`) запускает `fetchDeltaOrFull` (`:660`) **и** `probeLivenessNow` → `resumeSession` → `scheduleDeltaPoll` (`:682-686`, `:1353-1357`, `:1187-1221`) параллельно, без учёта in-flight initial load. Без кэша: `openSeq = openEpoch = 0` (`:555-557`); свежая сессия (`epoch == 0`, `model.rs:714`) → сервер отдаёт страницу 1 всего (10 полных entries + картинки) → `applyDeltaLocked` видит `_session` в `Loading` → `needsFullLoad` → второй `fetchFullSession` (`:1017-1027`, `:1082-1086`); старая сессия (`epoch > 0`) → `reset` → второй `fetchFullSession` (`:1207-1210`). `probeLivenessNow` — round-trip `capabilities`, так что на любом линке, где `get_session(count=50)` дольше RTT + 200 мс, дубликат гарантирован. С кэшем: оба caller'а снимают один `openSeq` до применения — одна и та же первая дельта-страница дважды. Reconnect во время открытия добавляет третий. Из-за serial loop сервера второй полный load ждёт за первым — пользователь видит первый результат, затем оплачивает второй download впустую.

**Сценарий отказа.** RTT 2 с, кэш пуст: `capabilities` в ~2 с, poll на 2.2 с, ответ ~4.5 с при ещё качающемся многомегабайтном `get_session` (N-30) → второй идентичный многомегабайтный download. На каждом открытии сессии с вытесненным кэшем (reset, compact, prune, schema bump) и с второго устройства.

**Верификация.** Трассировка.

**Исправление (client).** Трекать initial load как job; `resumeSession` во время него — no-op (или dirty-флаг из N-29); провести `fetchDeltaOrFull` через ту же single-flight poll-машину.

#### N-34 · MEDIUM · network-efficiency · server
**Источники:** server M3 (CONFIRMED).

**Описание.** `editor_mcp::notifications::emit` → `server.broadcast_notification` (`sawe/crates/editor_mcp/src/notifications.rs:13-23`) → `unbounded_send` сериализованного кадра в каждое соединение (`context_server/src/listener.rs:373-390`). `SubscriptionRegistry` пишется `sub_create`/`sub_delete` и читается только `sub_list` для `editor.list_subscriptions` (`tools/subscribe.rs:65,126,178`); в emit/broadcast-пути не используется; per-connection очистки нет (глобальный реестр `sub-N`, `subscriptions.rs:41-65` — каждый reconnect телефона добавляет запись). Единственный outbound-фильтр — `allow_list::should_forward_event`, проверка префикса kind без session/solution scoping (`allow_list.rs:88-100`). На каждое событие координатор шлёт типизированное уведомление **и** `agent_session_dirty` (`event_sources.rs:48-67,77-97`). `message_appended` несёт `stream_id/role/preview/client_send_id(s)/created_ms` (`:313-330`), телефон использует только `sessionId`/`entryIndex` (`SessionDetailStore.kt:802-811`); `SUBSCRIPTION_KINDS`, который телефон пересылает на каждом reconnect (`SessionListStore.kt:299-352`), ни на что не влияет.

**Сценарий отказа.** Десктоп гоняет 4-teammate task, телефон показывает одну сессию на LTE: ~2–4 KB/s уведомлений, которые он выбрасывает, каждое будит радио; DEFLATE на 130–400 B кадрах не помогает; `list_sessions` (N-32) срабатывает на каждый flip состояния teammate.

**Верификация.** Трассировка по `editor_mcp`/`context_server`/`remote_control`.

**Исправление (server).** Учитывать per-connection подписку в `proxy.rs` (там известен client name): kinds + опциональный `session_id`/`solution_id`; для remote-клиентов, доверяя `dirty`, не слать per-event `message_appended` (или класть `entry_index` в `dirty`); коалесцировать `dirty` per session per ~250 мс в прокси.

#### N-35 · LOW · network-efficiency (CPU) · client
**Источники:** transport L1 (CONFIRMED).

**Описание.** `dispatchJsonRpc` парсит `parseToJsonElement(text)` ради `id` (`RemoteClient.kt:1074`), затем `JsonRpc.decodeResponse(text)` парсит строку заново (`:1085`) — на OkHttp reader-потоке (`OkHttpRemoteTransport.kt:63-65`). Ответы `get_session`/`get_session_changes` — сотни KB; reader последовательный, так что каждый большой ответ задерживает следующее уведомление на лишний парс.

**Исправление (client).** `JsonRpc.json.decodeFromJsonElement(JsonRpcResponse.serializer(), parsed)`.

#### N-36 · LOW · network-efficiency (disk) · client
**Источники:** sync L2 (CONFIRMED).

**Описание.** `LastSeenIndex.kt:33`, `:53` определяют `getCached`/`readFromDisk`; грепом по `app/src/main` читателей нет (`SessionListStore` держит поле `:70`, не трогает; `SessionDetailStore` только `primeFromDisk`/`recordIfNewer`/`clear`, `:574`, `:809`, `:993`, `:1078`, `:504`). `LastSeenRepository.set` (`:72-79`) — `getInt` + `putInt().apply()` только при росте индекса: одна перезапись файла на новую запись, не на poll. Бейдж непрочитанных из него не рендерится.

**Исправление (client).** Удалить или подключить unread-badge и писать только на Idle.

#### N-37 · LOW · network-efficiency · server
**Источники:** server L7 (CONFIRMED).

**Описание.** `preview` вычисляется и шлётся безусловно рядом с `markdown` (`dto.rs:837-842`); tool-call `args_preview`/`result_preview` по 500 символов (`:1024-1033`) дублируют `markdown`, уже включающий `content_md` (`:741-757`); `streams: build_streams_vec(session)` на каждой дельте и странице (`read.rs:1319`, `reset`-ветка `:1162`); строки списка несут `acp_session_id`/`cwd`. Телефон использует `preview` только при `markdown == null` (`SessionDetailScreen.kt:1332,1387`). 200–1200 B на entry и ~120 B на stream на poll.

**Исправление (server).** Не слать `preview` при наличии `markdown`; `streams` — только при изменении watermark (или по хэшу от клиента).

#### N-38 · LOW · network-efficiency (documentation / tuning) · server
**Источники:** sync A2 (дополнительная находка верификатора).

**Описание.** Комментарий в `sawe/crates/solution_agent/src/store/acp_event.rs:772-830` обещает «roughly 2 emits/sec», реализация — перевзводимый debounce: каждый `EntryUpdated` заменяет `Task` (отменяя 500-мс таймер), эмит форсирует только 2-секундный `max_stale`; при непрерывном стриме ~1 emit / 2 с. Не баг сам по себе (меньше poke), но 200-мс debounce и 4-секундный tail-resync мобильного клиента размерены под задокументированный каданс; с реальным кадансом 4-секундный тик регулярно отменяет poll, который вот-вот завершится (N-29 b).

**Исправление (server / client).** Привести комментарий в соответствие с кодом или сделать 500 мс настоящим throttle (first-edge/fixed cadence), если целью были ~2 poke/s; на клиенте размерить tail-resync выше ожидаемого времени завершения poll'а или сделать его single-flight с живым poll'ом.

#### N-39 · LOW · bug (bytes) · client
**Источники:** queue L3 (CONFIRMED; репро воспроизведён).

**Описание.** `utf8Size` (`core/src/main/kotlin/ru/sipaha/sawe/core/WireCompression.kt:145-156`) считает high surrogate как 2, но low surrogate (U+DC00–DFFF) не проходит `isHighSurrogate` и попадает в `else -> 3` — пара стоит 5 вместо 4. Используется в `:83-91` для порога и «strictly smaller»-guard'а: `rawSize` завышен, порог срабатывает чуть раньше, и `frame.size < rawSize` может принять сжатый кадр на несколько байт *больше* сырого текста для emoji-плотных коротких сообщений. Декод не страдает — `compress()` пишет настоящий `raw.size` в заголовок (`:64`); сервер считает реальные байты (`wire_codec.rs:83-95`).

**Верификация.** Репро (reflection на приватный метод): `L3 counted=5 actual=4`.

**Исправление (client).** `Character.isLowSurrogate(c) -> 2` или просто `text.toByteArray(UTF_8).size` (массив всё равно строится в `compress`).

### 4.4 Загрузка вложений

#### N-40 · HIGH · network-resilience · client
**Источники:** upload H2 (CONFIRMED).

**Описание.** После последнего ack `runUploadLoop` вызывает `client.call("…upload_finish")` в `runCatching { }.mapCatching { }` и любой отказ маппит в `State.Failed("upload didn't complete — tap to retry")` + `cleanupOnTerminal` (`app/src/main/kotlin/ru/sipaha/sawe/app/vm/UploadManager.kt:657-678`); rethrow `CancellationException` нет (в отличие от init-цикла `:545/571` и chunk-цикла `:640`). Три транзиентные причины: (1) обрыв транспорта → `failPendingOnDisconnect` (`RemoteClient.kt:828-835`) → `IllegalStateException`; (2) `withTimeout(30_000)` в `call` (`:396-400`) — `TimeoutCancellationException` наследует `CancellationException`, ловится `runCatching`; finish-кадр стоит в едином FIFO OkHttp за чанками других загрузок (до 3×256 KiB при серверном cap 4/сессия), на ~100 kbps это ~63 с > 30 с (см. N-45); (3) `pauseAll` (`:276-293`) на Main: `job.cancel()` затем `flow.value = Paused(...)`; отменённая корутина позже возобновляется, `runCatching` глотает отмену, `Failed` перекрывает `Paused` (все scope — `viewModelScope`, порядок подтверждён). Сервер: `finish` (`sawe/crates/solution_agent/src/upload.rs:228-257`) только читает запись, проверяет `received_bytes == expected_size`, опциональный sha, возвращает handle; запись удаляется лишь `abort` (`:262-276`), resolve (`:540-544`) или GC — повторный `upload_finish` идемпотентен и вернёт тот же handle. Дисковая запись переживает (`cleanupOnTerminal` `:767-779` персист не трогает): следующий холодный старт оживил бы её и, при offset == total, сразу пошёл бы в `upload_finish` — но внутри процесса `Failed` терминален, и с N-04 отложенная отправка уже потеряна.

**Сценарий отказа.** 4 фото на слабом LTE; фото #1 первым добирает чанки и шлёт `upload_finish`; текстовый кадр ждёт за ~768 KiB чанков остальных → 30-секундный таймаут → `Failed` → отложенная отправка прервана, сообщение потеряно, пока фото 2–4 грузятся нормально. Или любой handover Wi-Fi→LTE в окне RTT после последнего ack.

**Верификация.** Трассировка + исходники OkHttp 5.3.0 (единый FIFO writer).

**Исправление (client).** Перебрасывать `CancellationException`; на транспортный отказ/таймаут переходить в `Paused(total, total, …)`, чтобы `resumeAll`/watchdog повторили `upload_status` → `upload_finish`; терминальным считать только `resp.error`/`toolError`.

#### N-41 · MEDIUM · network-resilience · client
**Источники:** upload M1 (CONFIRMED).

**Описание.** `UPLOAD_CHUNK_PAYLOAD_BYTES` = 256 KiB (`core/src/main/kotlin/ru/sipaha/sawe/core/UploadProtocol.kt:35`), `ACK_TIMEOUT_MS = 30_000` (`UploadManager.kt:860`); таймаут → `Paused("ack timeout — server may be unreachable")` и `return null` (`:739-746`), корутина завершается; авто-resume только по Connected edge (`MainViewModel.kt:373`) или watchdog (`UploadManager.kt:812-849`: опрос 5 с, порог 15 с → `resumeAll`). Ниже ~70 kbps каждый чанк (>30 с) выбивает таймаут: цикл Paused(15–20 с) → `upload_status` → один чанк → Paused, один чанк на ~45–50 с, UI большую часть времени «Paused» при здоровом линке. Отдельно: `runDeferredSend` оборачивает ожидание каждого вложения в `withTimeoutOrNull(DEFERRED_UPLOAD_TIMEOUT_MS)` (`SessionDetailStore.kt:1703`; 5 мин, `:91`) — абсолютное окно, не сбрасываемое продвижением `Uploading.sent`; сообщение `:1739` говорит «no progress within 5m» — неверно. 5 MiB / 300 с ≈ 17 KB/s ≈ 140 kbps — ниже этого 5-MB файл падает детерминированно, и с N-04 текст теряется. Сервер: NACK не эмитит (`upload_chunk_error` только в `allow_list.rs:307`, тест), ack-tick 100 мс (`solution_agent.rs:203-205`).

**Сценарий отказа.** 4.8 MB фото на LTE у края покрытия (~100 kbps): чанки по ~21 с (под ack-таймаутом), 19 чанков ≈ 6.8 мин → waiter истекает на 5:00 с «upload stalled — try again», отменяет рабочую загрузку, роняет пузырь и текст.

**Исправление (client).** Дешёвая половина — progress-based deferred timeout (сброс на каждое продвижение `sent`) или его удаление (`Failed`/`cancel()` уже разблокируют `first{}`); вторая — адаптивный размер чанка (64 KiB на медленных линках по измеренному ack-RTT) и/или ack-таймаут, масштабируемый размером чанка; не-acked чанк при `Connected` не должен давать Paused «unreachable».

#### N-42 · MEDIUM · persistence / network-resilience · client
**Источники:** upload M2 (CONFIRMED).

**Описание.** Пикеры `PickMultipleVisualMedia`/`OpenDocument` (`SessionDetailScreen.kt:3058-3117`); `grep "takePersistableUriPermission\|cacheDir\|FLAG_GRANT_PERSISTABLE"` по `app/src/main/kotlin` — нет (только `filesDir` для crash-логов). Персистится только `uriString` (`InFlightUploadsRepository.kt:24-33`). Грант photo-picker'а живёт «until the device is restarted or until your app stops»; непостоянный document-грант тоже не переживает process death → `openInputStream` бросает `SecurityException`. `UploadManager.kt:614-648`: любое не-cancellation исключение из `openInputStream`/`skip`/`pumpChunks` → `Paused(sent, total, it.message)` и `return`; watchdog (`:832-846`) видит `Paused && Connected` ≥15 с → `resumeAll` → новая корутина → `upload_status` (работает, `uploads.rs:158-177`) → `openInputStream` → то же исключение → Paused. Бесконечный цикл, один RPC на ~15–20 с, до серверного GC (`solution_agent.rs:234-251`, TTL `upload.rs:54`) → `unknown_upload_id` → `Failed` (`:491-499`). Waiter, оживлённый `resumeDeferredSendsFromDisk` (`SessionDetailStore.kt:1616-1651`), 5 мин наблюдает Paused/Uploading → «stalled» → `cleanupDeferred` → N-04. KDoc `UploadManager.kt:141-147` упоминает случай lapsed permission; обработчика нет.

**Сценарий отказа.** 4 MB фото в поезде, 40 %, переключение в другое приложение; Android убивает процесс; возврат: чип «40 % (paused)» мигает в «Uploading» и обратно каждые ~15 с, по RPC на цикл, 5 минут; потом пузырь исчезает с «upload stalled».

**Исправление (client).** Копировать выбранный файл в app-private storage (`cacheDir`, ≤ 5 MB) в момент выбора и персистить этот путь (или `takePersistableUriPermission`, где провайдер позволяет); `SecurityException`/`FileNotFoundException` из `openInputStream` — терминальный `Failed` с ясным «re-attach».

#### N-43 · MEDIUM · network-resilience / bug · client
**Источники:** upload M3 (CONFIRMED).

**Описание.** `UploadManager.kt:487-500`: сообщение с `unknown_upload_id`/`not found` → `Failed("upload expired on server — re-attach to retry")` + `cleanupOnTerminal`, хотя ветка `uploadId == null` (`:502-604`) умеет re-init с нуля и файл у клиента есть. Серверное состояние — in-memory `HashMap` (`upload.rs:92`), id засеяны wall-clock (`:109-115`) — рестарт десктопа или 1-часовой GC делают каждый персистентный id неизвестным. Смена сервера: `ConnectionManager.kt:346-368`: `onBeforeSwitch` → `tearDownConnection` (→ `MainViewModel.onTearDown` `:324-337` → только `pauseAll`; `states`/`metadata` не тронуты) → `_activeServerId = new` → `onClientBound` → `resumeAllFromDisk` (персист keyed by `activeServerProvider` — записи *нового* сервера) → `connect`. Первый Connected edge → `onReconnected` → `resumeAll` (`:300-306`) проходит по всем Paused в `states`, включая старого сервера: с id → `upload_status` не на том сервере → `unknown_upload_id` → Failed; без id → `upload_init` со старым `session_id` → `unknown_session` (`uploads.rs:90-92`) → non-retryable → `Failed("couldn't start upload — tap to retry")` (`:570-582`). `forgetAllForServer` (`:378-391`) чистит всё in-memory состояние независимо от сервера.

**Сценарий отказа.** 2 картинки прикреплены, десктоп перезапущен (обновление) до Send → оба чипа «upload expired on server — re-attach», Send выключен (`anyUploadFailed`), пока каждый чип не удалён и не выбран заново. Или: смена активного сервера при paused-загрузке → загрузка падает на другом сервере, с pending deferred send текст теряется (N-04).

**Исправление (client).** На `unknown_upload_id` при resume — очистить `meta.uploadId`, удалить дисковую запись и провалиться в init-ветку (рестарт с 0), fail только если `openInputStream` не удаётся; тегировать `UploadMetadata` server id (или чистить non-active записи в `onTearDown`), чтобы `resumeAll` трогал только загрузки привязанного сервера.

#### N-44 · MEDIUM · bug / network-efficiency · both
**Источники:** upload M5 (CONFIRMED).

**Описание.** Сервер отказывает `upload_init` при 4 записях в сессии (`upload.rs:41, 139-148`), считая Done-но-не-consumed; удаление только `abort` (`:262`), успешный resolve (`:540-544`), GC (`:280-295`, TTL 1 ч, тик 5 мин `solution_agent.rs:238`); провал resolve (например `unsupported_mime`, `:519`) выходит до цикла `consumed`, оставляя записи живыми. Клиент: `forget()` (`UploadManager.kt:361-370`) никогда не вызывает `upload_abort`; `cleanupDeferred` использует `forget` на всех путях (`SessionDetailStore.kt:1904-1906`); all-Done путь забывает сразу после `onSend` независимо от исхода (`SessionDetailScreen.kt:3428-3429`); `cancel()` (`:314-353`) абортит только при `meta.uploadId != null` — во время in-flight `upload_init` запрос уже на проводе, `cont.invokeOnCancellation` лишь снимает pending (`RemoteClient.kt:424-426`), сервер выделяет слот, никто его не абортит. Mime: file picker `arrayOf("*/*")` (`:3499`); `resolvePickedAttachment` (`:3536-…`) allow-list не проверяет (серверный — `image/*` или `is_text_like`, `upload.rs:401-416`). Ошибка init non-retryable → `Failed("couldn't start upload — tap to retry")` (`:570-582`); Send выключен при любом Failed-чипе (`:3181-3189`). 4 фото (`MAX_PHOTOS_PER_PICK`) + 1 файл = 5 init > cap детерминированно.

**Сценарий отказа.** `report.pdf` + текст, Send; сервер — `unsupported_mime`; клиент забывает без abort; Done-запись PDF занимает слот час. Затем 4 фото → 4-е падает на init «couldn't start upload — tap to retry»; тап показывает диалог с причиной, retry нет; Send заблокирован, пока чип не удалён.

**Исправление (both).** Client: слать `upload_abort` из `forget()`/`cleanupDeferred` на путях отказа, в `cancel()` откладывать abort до разрешения pending init; валидировать mime при выборе против серверного списка; ошибку «concurrent uploads» считать retryable-with-backoff или показывать отдельно. Server: обновлять TTL на каждый чанк (см. N-55).

#### N-45 · MEDIUM · network-resilience · client
**Источники:** upload M6 (CONFIRMED по исходникам OkHttp).

**Описание.** OkHttp 5.3.0 `RealWebSocket`: `send(text)`/`send(bytes)` идут через `@Synchronized send(data, opcode)` (`:438-454`) в единый `messageAndCloseQueue` (`ArrayDeque`, `:102`); `writeOneFrame()` (`:521-570`) берёт pong'и, затем **одно** сообщение и `writer.writeMessageFrame(...)` целиком; `WebSocketWriter.writeMessageFrame` (`:154-208`) пишет весь кадр в sink и делает `sink.flush()` — writer блокируется до полной отправки 256 KiB перед следующим элементом. Строгий FIFO без interleaving, единственный back-pressure — `MAX_QUEUE_SIZE = 16 MiB` (`:684`). `queueSize()` (`:141`) через `RemoteTransport` не экспонируется (`OkHttpRemoteTransport.kt:116-122`; грепом в `core/` нет). Heartbeat (`ConnectionManager.kt:611-670`): 30 с / 8 с / 2 промаха → `forceReconnect` (`:666`). До 4 uploads × 1 in-flight чанк ≈ 1 MiB перед ping'ом; на 200 kbps это ~40 с. Два промаха → teardown → `onConnectionInterrupted` → `pauseAll` (`MainViewModel.kt:376-385`) → in-flight чанки потеряны → resume с серверного offset → повтор. Та же очередь задерживает каждый `call` (30-секундный таймаут) — `get_session_changes`, `send_message`. Серверный HOL пренебрежим (синхронный `write_chunk`, `listener.rs:1101-1113`, `upload.rs:179-216`).

**Сценарий отказа.** 4 фото на 200 kbps (чанк ≈ 10 с, 4 в очереди ≈ 40 с): heartbeat на t=30 истекает на 38, на t=60 — на 68 → force-reconnect на ~68 с с «Server stopped responding — reconnecting…»; загрузки паузятся, теряют in-flight чанки, цикл ~раз в минуту; RPC чата стоят всё это время.

**Исправление (client).** Экспонировать `queueSize()` через `RemoteTransport` и гейтить `sendBinary` по нему (или cap параллельных загрузок 1–2); меньшие чанки на медленных линках (N-41); heartbeat считать недавний `upload_chunk_acked` доказательством живости.

#### N-46 · MEDIUM · bug / network-resilience · client
**Источники:** upload M7 (дополнительная, MEDIUM) + upload L1 (CONFIRMED-UPGRADED → MEDIUM по той же причине).

**Описание.** `connectTo` публикует `client` (`ConnectionManager.kt:363`) и вызывает `lifecycle.onClientBound` (`:367`) *до* `newClient.connect(scope)` (`:368`). `resumeAllFromDisk` (`MainViewModel.kt:303-322`) поэтому запускает корутину на каждую персистентную загрузку при `transport == null`; `activeClient()` non-null, guard `client == null` (`UploadManager.kt:431-454`) пропущен (фактически мёртвая ветка на cold-start), `upload_status` синхронно бросает `NotConnectedException` (`RemoteClient.kt:409-411`), сообщение не содержит `unknown_upload_id`/`not found` → корутина завершается в `State.Paused(meta.uploadId ?: 0L, meta.totalSize, "resume failed: $msg")` (`:496`) — **id загрузки (≈1.7×10⁹, wall-clock) как байты `sent`**. Чип считает `sent*100/total` с coerce до 100 → «100% (paused)» (`SessionDetailScreen.kt:3800-3801`), пузырь печатает `formatBytes(sentBytes)` (`:1683-1690`); deferred progress badge (`SessionDetailStore.kt:1711-1712, 1722`) показывает `bytesDoneFromPrior + uploadId`. То же при `pauseAll`, отменяющем корутину в `upload_status` (отмена проглочена `runCatching` `:467`, перекрывает корректный `Paused(sent,…)`). Самолечится после Connected edge → `resumeAll`; при неудачной первой попытке connect — держится весь backoff.

**Сценарий отказа.** Приложение убито на 40 % от 3 MB → перезапуск → пузырь «Paused at 1.6 GB / 3.0 MB» при баннере Connecting; исправляется после handshake.

**Исправление (client).** Вызывать `resumeAllFromDisk` из первого `onReconnected` (или в `runUploadLoop` трактовать `NotConnectedException` от `upload_status` как ветку no-client: сохранять прежний `Paused(sent)` без RPC); в `:496` использовать last-known `Paused`/`Uploading.sent` или персистентный `lastConfirmedOffset`; rethrow `CancellationException` (N-28).

#### N-47 · LOW · bug / network-efficiency · both
**Источники:** upload L2 (CONFIRMED).

**Описание.** (1) `stream.skip(startOffset)` вызывается один раз (`UploadManager.kt:617-621`), `error(...)` при short skip → ловится `:636-648` → `Paused` (не `Failed`) → watchdog повторяет вечно (pipe-backed провайдеры, облачные документы). (2) `pumpChunks` (`:706-707`) читает `buf.size` независимо от `totalSize - offset`; при заниженном `_size` последний чанк переполняет — сервер отвергает «overrun» (`upload.rs:190-196`), listener только логирует (`listener.rs:1103-1108`), `upload_chunk_error` нигде не эмитится → 30 с ack-timeout → Paused → watchdog → тот же чанк: цикл ~45 с с пересылкой 256 KiB. Crash-путей (IOOBE/NPE) не найдено: `buildUploadChunkFrame` точен, `uploadId` non-null, DTO с `ignoreUnknownKeys` в `runCatching`.

**Сценарий отказа.** Документ Google Drive со stale `_size` после правки: «Uploading 98 %» → «paused» каждые ~45 с до 5-минутного таймаута, потом сообщение потеряно (N-04).

**Исправление (both).** Client: цикл `skip` до нужного offset (или read-and-discard); clamp чтения `min(buf.size, totalSize - offset)`, терминальный fail при лишних байтах. Server: эмитить `upload_chunk_error` (уже в allow-list) — см. N-55.

#### N-48 · LOW · bug (UI lie) · client
**Источники:** upload L4 (CONFIRMED).

**Описание.** Строки `Failed` обещают «tap to retry» (`UploadManager.kt:580, 675`); карточка кликабельна только в Failed (`SessionDetailScreen.kt:3598`) и открывает `AlertDialog` с одной кнопкой OK (`:3721-3748`); KDoc `:3571-3574` прямо говорит «tap-to-retry isn't wired». `anyUploadFailed` выключает Send (`:3181, 3188`); единственное восстановление — × (→ `upload_abort`, `:3252-3260`) и повторный выбор.

**Исправление (client).** Реальный retry (`start()` с теми же URI/metadata под тем же localKey) или текст «remove and re-attach». Необходим для N-04 (сохранять `Failed` вместо `forget()`).

#### N-49 · LOW · bug (main-thread I/O) · client
**Источники:** upload L3 (CONFIRMED).

**Описание.** `remember(attachment.uri) { runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(..., inSampleSize = 4) } } }` выполняется в composition на UI-потоке (`SessionDetailScreen.kt:3613-3621`); `remember` теряется при remount; для 12 MP JPEG — ~1000×750 decode (≈3 MB) + content-provider I/O на карточку. Восстановленные вложения несут `Uri.EMPTY` (`SessionDetailStore.kt:2320`) → placeholder.

**Сценарий отказа.** Выбор 4 фото замораживает compose bar на сотни мс на mid-range телефоне; навигация туда-обратно повторяет.

**Исправление (client).** `produceState`/`LaunchedEffect` на `Dispatchers.IO` (или `ImageDecoder`/Coil с target size); кэш по `localKey`.

### 4.5 Контракт с сервером (что чинить на стороне sawe)

Серверные части кластерных находок описаны в своих записях: whole-entry delta и `mod_seq` per chunk (N-29), base64-картинки и default `include_images` (N-30), `remote.editor.ping` и два pong'а (N-31), `state` в payload `state_changed` (N-32), fan-out уведомлений (N-34), избыточные поля (N-37), throttle-комментарий (N-38), close 1009 на oversize (N-08), csid-dedupe (N-05), `mcp_unavailable` (N-11), TTL/NACK загрузок (N-44, N-47, N-55). Ниже — чисто серверные.

#### N-50 · MEDIUM · network-resilience / concurrency · server
**Источники:** server M1 (CONFIRMED) = sync A1 (дополнительная, MEDIUM).

**Описание.** `run_request_loop` (`sawe/crates/remote_control/src/listener.rs:974-998`) — один `select!`, чьи ветви лишь *производят* `SelectOutcome`; работа идёт в `match` после select. В ветке `Frame(Some(Ok(frame)))` запрос диспатчится **inline**: `dispatcher_ref.dispatch(client_name, req).await` (`:1157`), затем `write_response(ws, ...).await` (`:1161`); ничего не spawn'ится. Пока любой await висит, цикл не в `select!`: `ws.next()` не поллится (inbound Ping не читается → нет явного Pong `:1022-1027`; auto-pong tungstenite тоже ставится в очередь только на read и флашится на следующем write), `rx.recv()` не поллится (уведомления копятся в 256-слотном mpsc — N-52), idle-`sleep` не существует (пересоздаётся на итерацию, `:985/:994`). Dispatch может блокировать до `CALL_TIMEOUT = 30 s` (`proxy.rs:41`); `send_text_frame` — пока ядро не примет кадр. Отправка уведомлений тоже inline (`:1209`). Следствия для клиента: ответы строго в порядке запросов (что, кстати, убивает reorder-гонки sync M3/L3), отменённый клиентом запрос не только исполняется, но и HOL-блокирует замену и все уведомления за ним (усиливает N-29 b/c, N-32, N-33); телефон не может отличить «сервер занят моим же большим ответом» от zombie-сокета.

**Сценарий отказа.** Открытие сессии с 4 фото на LTE (≈ 25 MB ответ, > 30 с); агент заканчивает ход — `message_appended`/`dirty`/`state_changed` лежат в серверной очереди до конца записи; тап Stop → `cancel_turn` встаёт за ним; liveness-проба истекает → `forceReconnect`, download убит. Также `create_session`/`list_sessions` с `hydrate_all_for_solution` (`read.rs:124-131`) > 30 с на редакторе → OkHttp ping/pong watchdog убивает сокет («sent ping but didn't receive pong»).

**Исправление (server; client — до тех пор).** Разделить соединение на reader- и writer-задачи через mpsc (ответы + уведомления), spawn'ить каждый `dispatch`, back-pressure в writer'е; idle-таймер — только по inbound. Минимальная промежуточная мера — обернуть только `dispatch` в `select!` с `ws.next()`, чтобы ping/pong шли во время медленных RPC (большая запись всё равно требует writer-задачи). Client: single-flight poll (N-29), не издавать второе тяжёлое чтение при живом первом (N-33), уменьшить тяжёлые чтения (N-30), heartbeat терпимый к in-flight большому ответу.

#### N-51 · MEDIUM · bug / security · server
**Источники:** server M2 (CONFIRMED).

**Описание.** `set_enabled(false)` → `stop_listener` (`sawe/crates/remote_control/src/store.rs:153-166`) только `take()`'ит `ListenerHandle`, чистит `cert_fingerprint`, `clients_tx`, `_bootstrap` (`:261-268`). `ListenerHandle { shutdown_tx, bound_addr, task }` (`listener.rs:257-261`) не ссылается на `ListenerState`/`active_conns`; `Drop` шлёт shutdown oneshot и абортит только accept-loop `JoinHandle` (`:270-281`). Per-connection задачи — detached `tokio::spawn(handle_conn(...))` (`:395-411`); `handle_conn` (`:612-618`) не принимает shutdown receiver; `select!` в `run_request_loop` имеет только `kill_rx` (eviction/revocation), `ws.next()`, уведомления, idle sleep (`:974-998`). Drop `clients_tx` тихо завершает `watch_revocations` (`:571-573`) без kick'ов. `active_conns` итерируется только в `kick_existing_for_client`, `watch_revocations`, slot push (`:814`), `SlotGuard::drop`. Per-connection `UnixMcpProxy` говорит с `editor_mcp` Unix-сокетом, независимым от listener'а — RPC и уведомления работают, 30-секундные ping'и и heartbeat телефона перевзводят idle-таймер. Doc comment `:283-285` («dropping it shuts down the listener and any in-flight connections») ложен.

**Сценарий отказа.** Пользователь выключает Remote Control в редакторе, ожидая отсечь телефон; телефон остаётся `Connected`, получает все уведомления, может слать сообщения и авторизовать tool calls, пока случайно не переподключится (тогда TCP refused). Обратно: телефон показывает «Connected» для сервера, который считает себя выключенным.

**Исправление (server).** Держать `Arc<ListenerState>` в `ListenerHandle` и в `Drop` дренировать `active_conns`, стреляя каждым `kill` (существующая ветка `Evicted` уже шлёт чистый 1001 — дать отдельный reason «server shutting down»); либо передавать `watch` shutdown receiver в `select!` `run_request_loop`.

#### N-52 · MEDIUM · network-resilience / bug · server
**Источники:** server M5 (CONFIRMED).

**Описание.** `NOTIFICATION_QUEUE_CAPACITY = 256` с doc «drop the OLDEST» (`sawe/crates/remote_control/src/proxy.rs:42-48`). Reader делает `notifications_tx.try_send(frame)`; на `TrySendError::Full(value)` логирует «dropping oldest» и `drop(value)` — **новый** кадр (`:255-279`; inline-комментарий `:263-276` признаёт инверсию). Переполнение достигается ровно тогда, когда WS-задача застряла в inline `dispatch`/`ws.send` (N-50) — ~1–2 мин занятого десктопа при 2–4 кадрах/с. `upload_chunk_acked` идут через ту же очередь (уведомления из ack-pump `solution_agent.rs`, префикс `upload_` проходит `should_forward_event`). Последний `agent_session_dirty` — тот, на который опирается convergence-цикл; drop newest → у телефона остаётся старая, низшая цель `current_seq`, лечит только 60-секундный safety-net (`SessionDetailStore.kt:129,750`). Потерянный ack → chunk-цикл ждёт 30 с, `Paused` (`UploadManager.kt:731-746`), resume только через watchdog.

**Сценарий отказа.** Телефон грузит 3 MB (12 чанков), десктоп стримит 3 сессии, телефон посреди download'а большой дельты на медленном линке: ack чанка k отброшен → 30 с stall → Paused → позже resume через `upload_status`; пользователь видит замёрзший прогресс и «paused» на рабочем соединении.

**Исправление (server).** Реализовать drop-oldest (небольшой `VecDeque` под mutex + `Notify`, или `tokio::sync::broadcast` с lag-обработкой), никогда не ронять `upload_*`; лучше — коалесцировать по `(kind, session_id)`, оставляя последний кадр на ключ (поскольку `dirty` несёт *текущий* `change_seq`, это строго лучше drop-oldest и меньше).

#### N-53 · LOW · leak / network-resilience · server
**Источники:** server L1 (CONFIRMED).

**Описание.** Idle-`sleep` пересоздаётся на каждой итерации (`listener.rs:985,994`), так что доставка уведомления перевзводит его — это не таймер inbound-тишины; `send_text_frame(...).await` для уведомлений вне `select!` (`:1209`); `kick_existing_for_client` шлёт только по `kill_tx` (`:540-558`), который наблюдает лишь ветвь `select!`. `SO_KEEPALIVE`/`TCP_USER_TIMEOUT` на принятых сокетах не ставятся (только `set_nodelay`, `:621`). Телефон, исчезнувший посреди хода (half-open), не закрывается по idle, пока уведомления текут; при заполнении send-буфера задача блокируется в `ws.send` до TCP give-up (tcp_retries2 ≈ 15 мин), после чего `ws.send` ошибается, задача выходит, `SlotGuard::drop` снимает слот (`:834-850`). Каждая такая задача держит свой `UnixMcpProxy` — редактор сериализует каждый broadcast лишний раз на zombie.

**Сценарий отказа.** LTE, IP меняется 3× в час во время длинного хода → 3 zombie-задачи + 3 лишних MCP-соединения до получаса каждое; пользователю не видно, ограниченный расход.

**Исправление (server).** Вытекает из reader/writer split (N-50: writer select'ит на `kill_rx` и write-timeout); промежуточно — `socket2::SockRef::set_tcp_user_timeout` на принятом `TcpStream`; idle-таймер только по inbound.

#### N-54 · LOW · network-resilience · server
**Источники:** server L5 (CONFIRMED).

**Описание.** `subnet_key` обнуляет последний октет IPv4 / последние 64 бита IPv6 (`listener.rs:228-240`); лестница `BAN_BACKOFF_SECS = [30, 300, 3600, 86400]` с grace на 1 неудачу (`:49-60,449-512`); забаненные peer'ы сбрасываются на accept без close (`:354-364`); revoked-клиенты идут через тот же `kill_rx` и получают `1001 "evicted by new connection"` (`:564-607,1232-1236`). Pre-auth-ошибки не считаются (`:649-658`), retry во время бана не эскалирует. Операторы ставят тысячи абонентов за общими /24: чужая stale-пара, ротировавшая секрет и повторяющая попытки, банит телефон пользователя на 30 с–24 ч; телефон видит только `Unreachable` (TCP reset/EOF).

**Сценарий отказа.** Телефон пользователя и чужая stale-пара делят CGNAT /24; чужое приложение повторяет → лестница до 1 ч → пользователь заперт на час с «Connection failed».

**Исправление (server).** Бан по полному IP для CGNAT-диапазонов (100.64/10) или по (IP, заявленное client name); отдельный close-reason на revoke.

#### N-55 · LOW · network-resilience · server
**Источники:** server L6 (CONFIRMED).

**Описание.** Ошибки binary-frame-хендлера только `log::warn!` (`listener.rs:1101-1122`); `write_chunk` выходит при `offset != received_bytes` и при overrun без ack/уведомления (`upload.rs:179-197`); `gc` сравнивает `now - created_at > ttl` (`:280-295`, `UPLOAD_TTL = 1 h`, `:54`) — TTL от создания, не от последней активности; `resolve_upload_handles` вызывает `manager.abort(id)` на каждый consumed handle после одной отправки (`:539-544`), так что дважды воспроизведённое сообщение (N-05/N-06) падает с `unknown_upload_id`. Клиент ждёт `ACK_TIMEOUT_MS` и уходит в `Paused` (`UploadManager.kt:731-746`).

**Сценарий отказа.** Линк потерян сразу после записи чанка k, но до ack; на resume `upload_status` говорит k+1 — ок. Но если корутина не была отменена (half-open), она пересылает чанк k → тихий reject → 30 с stall → Paused → watchdog → resume. Медленно, но восстанавливается. Загрузка, суммарно простоявшая >1 ч (долгий outage), GC'ится → «upload expired» при байтах на диске.

**Исправление (server).** Эмитить `upload_chunk_rejected { upload_id, expected_offset }` (allow-list уже пропускает `upload_*`) — самое ценное для flaky-линков; считать точный дубликат `(offset, len)` идемпотентным re-ack; обновлять TTL на каждый чанк.

### 4.6 UI и персистентность (main-thread I/O, stuck-экраны, потерянные ошибки, crash-риски)

#### N-56 · MEDIUM · bug (main-thread I/O) · client
**Источники (группа):** queue M3 (CONFIRMED; уточнение про `apply()`) + connmgr L1 (CONFIRMED; clobber не «кратковременный») + sync M4 (CONFIRMED; каданс записи скорректирован) + ui M3 (CONFIRMED).

**Описание.** Весь `RemoteClient` живёт на `viewModelScope` = `Dispatchers.Main.immediate` (`MainViewModel.kt:153-157` → `ConnectionManager.kt:366` `connect(scope)`), `SessionDetailStore` — тоже (`MainViewModel.kt:219-220`). Точки блокирующего I/O на Main:
- *Холодный старт:* `MainActivity.kt:31-32` → `coldStartLandingRoute` (`ConnectionManager.kt:524-533`) → `pairingRepository.loadAll()` → lazy `PairingRepository.prefs` (`:45-66`, `AppMasterKey.get` + `EncryptedSharedPreferences.create` = Tink keyset unwrap через AndroidKeyStore; десятки–сотни мс, секунды на медленных TEE); `switchToServerLocked` на Main: `:357 drainExpiredQueueEntries()` → `EncryptedQueueStore.loadAll()` (`EncryptedQueueStore.kt:63,70-87`, второе открытие); `:367 lifecycle.onClientBound` → `uploadManager.resumeAllFromDisk()` (`UploadManager.kt:241-242` → `InFlightUploadsRepository.list()`, lazy `:68`) и `sessionDetail.resumeDeferredSendsFromDisk` (`SessionDetailStore.kt:1616-1620` → `PendingSendsRepository.list()`, lazy `:73`) — **четыре** encrypted-prefs файла на Main за старт; `RemoteClient.connect` → `controller.rehydrate()` → `loadAll()` (`RemoteClient.kt:256`). Асинхронная гидрация в `ConnectionManager.init` (`:129-142`) избыточна и, если её `withContext(Main)` ляжет после `:348`, перезаписывает `_activeServerId` значением, прочитанным до `setActive` — при отсутствующем/висячем persisted key in-memory id остаётся `null`/dangling **до следующего switch** (не «кратко»), и все per-server репозитории (`DraftRepository`, `LastSeenRepository`, `NavStateRepository`) пишут под `null`-scope; редко → LOW-часть.
- *Первый Send:* Compose `onSend` (`SessionDetailScreen.kt:516-527`) → `sendMessageBlocks` (`SessionDetailStore.kt:1441`) → `pendingSendsRepository.saveOrUpdate` (`:1473-1483`) синхронно **до** `scope.launch` (`:1484`) → lazy `openPrefs()` (`PendingSendsRepository.kt:73-86`) на потоке тапа. **Коррекция:** сама запись маркера — `androidx.core.content.edit {}` = `apply()` (`:93`), асинхронная; на caller'е только AES-SIV/GCM-шифрование.
- *Каждая офлайн-отправка:* `queueCall` → `synchronized(stateLock) { queueStore.add(message) }` (`QueueController.kt:151-167`) → `EncryptedQueueStore.add` → `writeBlob()` → полный AES-GCM re-encrypt + `commit()` (синхронная запись + fsync, `EncryptedQueueStore.kt:89-96`, `:227-245`) на Main, удерживая `stateLock`, который нужен и `callInternal` (`RemoteClient.kt:409-414`). Компрессия `Deflater(BEST_COMPRESSION)` (`WireCompression.kt:52-73`) — тоже на Main для fast-path и flush.
- *Каждое открытие чата:* `DisposableEffect(sessionId) { viewModel.openSession(sessionId) }` (`SessionDetailScreen.kt:239-242`) → `sessionHistoryRepository.load(sessionId)` синхронно до `scope.launch` (`SessionDetailStore.kt:553`) → lazy `prefs` (`SessionHistoryRepository.kt:67`, `:73-83`), `getString` = AES-GCM decrypt + `JSON.decodeFromString` до 50 `EntrySummary` с полным markdown (`:87-103`); плюс `remember(sessionId) { initialAttachments() }` → `AttachmentDraftRepository.load` в composition (`SessionDetailScreen.kt:3046-3048`).
- *Запись кэша (sync M4):* `persistCache` после каждой применённой дельты (`SessionDetailStore.kt:1089-1091`) → `save` debounce 500 мс на IO (`:110-122`) → `writeNow` пересериализует и шифрует всё окно и `apply()`'ит (`:197-215`; SharedPreferences переписывает весь файл со всеми сессиями); `entries` без cap. **Коррекция:** с реальным кадансом poke (~1 / 2 с на стримящийся entry) стационарная частота перезаписи ~30/мин, не 120/мин; во время шторма N-29 debounce перевзводится непрерывно и запись не ложится вовсе.
`StrictMode` в `app/src/main` не сконфигурирован — в debug ничего этого не ловит.

**Сценарий отказа.** Mid-range устройство, первый запуск после установки/обновления: 300–800 мс stall Main в `onCreate` + первый кадр; первый Send — видимый hitch (Keystore init в обработчике тапа); каждое открытие чата с крупной кэшированной страницей — сотни мс freeze перехода, на медленном keystore — ANR; каждая офлайн-отправка — синхронный fsync на Main; 5 сессий по ~300 KB, минута стрима — десятки перезаписей ~1.5 MB файла.

**Верификация.** Трассировка потоков четырьмя верификаторами.

**Исправление (client).** Открывать все encrypted prefs eagerly на `Dispatchers.IO` при конструкции ViewModel (как уже делает `ConnectionManager.init` для `PairingRepository`, `:134`) и убрать дублирующую гидрацию; `saveOrUpdate` — внутри launched-корутины с `withContext(IO)`; `RemoteClient` — на `Dispatchers.Default`-backed child scope (код очереди lock-based, не Main-confined); `drainExpiredQueueEntries` и оба чтения в `onClientBound` под `withContext(IO)`; `sessionHistoryRepository.load` внутри корутины на IO (сначала публиковать `Loading`), `AttachmentDraftRepository` через `LaunchedEffect`; кэш — один ключ на сессию (или БД), медленнее debounce при `Running` (3–5 с), cap entries (например последние 100).

#### N-57 · MEDIUM · bug (silent failure) · client
**Источники:** ui M1 (CONFIRMED) + ui M6 (дополнительная, MEDIUM, расширение).

**Описание.** `MainViewModel.kt:150-151`: `MutableSharedFlow<String>(extraBufferCapacity = 8)`, replay 0 — `tryEmit` без подписчиков отбрасывается. Эмиттеры: 43 `emitError(` + `lifecycle.onError` (`:395-396,404-405`). Коллекторы (`grep sendError` по `app/src/main`): ровно два — `SessionDetailScreen.kt:253` и `SolutionProjectsScreen.kt:81`, оба `LaunchedEffect` внутри route-composables, покидающие композицию, когда сверху `workspace`. `SnackbarHost(` только в `QrPairingScreen`, `SessionDetailScreen`, `SolutionProjectsScreen`; `WorkspaceScreen.kt` — ни коллектора, ни host'а (единственная поверхность — `WorkspaceUiState.Error` → `ErrorState(msg)` `:88,391`). Теряются: `createSession` → `emitError(notConnectedMessage())` (`SessionListStore.kt:616-620`) и ветки отказа `:646-657`, `deleteSession`, rollback `closeSolutionOptimistic`, `openSolution`; `NewSessionDialog.kt:58-61` обещает «error surfaces via the parent screen's snackbar» — родитель его не имеет. (M6) `ConnectionManager.editServer` возвращает синхронные строки только для пустого host / плохого порта; parse-level ошибки внутри корутины («Server not found», «no query — re-pair», «Address invalid», `:264-278`) идут в `onError` → `sendError`, у которого нет подписчика при `settings` сверху (`SettingsScreen.kt` без коллектора/host'а) — диалог закрывается, ничего не показано.

**Сценарий отказа.** На нестабильном LTE «New console» → Create при `Reconnecting`: `createSession` сразу `emitError(notConnectedMessage())`, диалог открыт, Create активна, пользователь повторяет без обратной связи. «Delete session» истекает через 30 с — строка остаётся, тишина. Правка host на то, что `PairingUrl.parse` отвергает → диалог закрылся, сервер не изменён, сообщения нет.

**Исправление (client).** Один `SnackbarHost` + коллектор `sendError` на уровне `AppNav` (или свои в Workspace/Settings) и/или `Channel`/`replay = 1`, чтобы эмиссии между экранами не терялись; ошибки `editServer` возвращать синхронно.

#### N-58 · LOW · crash · client
**Источники:** ui L1 (CONFIRMED).

**Описание.** `rememberSaveable(sessionId) { mutableStateOf(initialDraft) }` (`SessionDetailScreen.kt:3123`) без явного saver — `autoSaver` кладёт `String` в saved state NavBackStackEntry → Activity bundle. Ограничений нет: `BasicTextField` (`:3305-3318`) `maxLines = 6` — только визуально, `DraftRepository` без лимита, путь отправки без cap. Вставленный многосот-KB лог уходит в `onSaveInstanceState`; выше ~500 KB Binder-бюджета — `TransactionTooLargeException`, приложение умирает при сворачивании. Черновик и так на диске (`DraftRepository`) — копия в bundle избыточна. Тот же класс: `NewSessionDialog.kt:88-89` `initialMessage`/`sessionTitle` в `rememberSaveable`.

**Сценарий отказа.** Вставить 1 MB build log, нажать Home → crash.

**Исправление (client).** `remember` с seed из дискового черновика (уже загружается `loadDraftSeed`) или custom saver с cap.

#### N-59 · LOW · crash (hardening) · client
**Источники:** ui L2 (CONFIRMED-DOWNGRADED → LOW: только класс `date:`; сценарий `csid:` отвергнут).

**Описание.** Ключи LazyColumn: `date:<epochDay>`, `queued:`, `csid:<clientSendId>`, `idx:`, `pos<i>` (`SessionDetailScreen.kt:1036-1062`); дедуп только для `idx:` (`:834-846`, `dedupeEntriesByIndex`). `date:` — `ChatTimeline.kt:26-33` эмитит разделитель на каждом `date != lastDate`; `createdMs` есть только у серверных entries, ставится при append, так что дубликат требует шага часов десктопа назад через локальную полночь между двумя соседними записями — реально, но экзотично. **Отвергнуто:** `csid:` — генератор `AtomicLong(System.currentTimeMillis())` (`SessionDetailStore.kt:282`) +1 на send; коллизия требует, чтобы `startMillis + n` нового процесса совпал с `startMillis + m` старого, т.е. wall-clock старта в пределах нескольких мс от прежнего; «часы назад на 10 мин» даёт seed на 600 000 ниже — нужны ~600 k отправок; два телефона — старт в одном ms-окне. Replay отправки с потерянным ответом не существует (`callInternal` роняет in-flight с `IllegalStateException`, `queueCall` re-queue только на `NotConnectedException`) — это потеря (N-01), не коллизия ключей.

**Исправление (client).** Для `date:` — fallback на `pos<i>` при повторе ключа в derivation pass; random-high-bits в seed csid — гигиена, для этой находки не нужна.

#### N-60 · LOW · bug / cosmetic · client
**Источники:** ui L4 (CONFIRMED; ссылки на строки исправлены).

**Описание.** `CrashLogsScreen.kt` — 190 строк (ссылки ревьюера `:472`/`:545` неверны): `:62` `remember { mutableStateOf(CrashLogger.listCrashFiles(context)) }` — `listFiles` на Main; `:135` `CrashLogger.readCrashFile(file)` внутри `Text(...)` диалога — перечитывается на каждой recomposition, Main; `:187` снова для share. `SettingsScreen.kt:177` `remember { CrashLogger.listCrashFiles(context).size }`. Баннер: `ConnectionStatusBanner.kt:103-106` `remember { mutableStateOf<Long?>(null) }` + `LaunchedEffect(unhealthy)` — config change перезапускает композицию и перештамповывает `unhealthySinceMs`, 15-секундное тихое окно стартует заново посреди outage (косметика). QR: `QrPairingScreen.kt:118-122` `LaunchedEffect(error)` по строке — одинаковая вторая ошибка не показывается; отказ в разрешении камеры (`:87-101`) — снэкбар без перехода в настройки приложения. Файлы — KB, только jank.

**Исправление (client).** `remember(file) { readCrashFile(file) }` / IO; `rememberSaveable` для `unhealthySinceMs`; эффект ошибки по счётчику; «Open settings», когда `shouldShowRequestPermissionRationale` false.

#### N-61 · LOW · persistence · client
**Источники:** queue L4 (CONFIRMED-DOWNGRADED: уже LOW, класс триггеров сужен).

**Описание.** `AppMasterKey.kt:32-44` тихо создаёт новый ключ при потере Keystore; `EncryptedQueueStore.kt:63,70-81` и `PendingSendsRepository.kt:73-86`: отказ `EncryptedSharedPreferences.create` (Tink keyset внутри `spk_queue.xml`/`spk_pending_sends.xml` не расшифровывается новым мастер-ключом) → `runCatching` → `prefs == null` на жизнь процесса; файл prefs не удаляется — повторяется на каждом запуске. Повреждённые *значения* обрабатываются (`EncryptedQueueStore.kt:180-190`), повреждённый/нерасшифровываемый *keyset* — нет. Офлайн-отправки становятся memory-only без сигнала пользователю (`EncryptedQueueStore` держит in-memory кэш `:64-68`, текущий процесс работает; потеря — через process death). **Коррекция:** `android:allowBackup="false"` (`AndroidManifest.xml:21`) — триггер «backup restore на другое устройство» не применим; остаются потеря Keystore-ключа (OEM-баги обновлений, сброс credential-store).

**Исправление (client).** На отказ `create()` — `context.deleteSharedPreferences(PREFS_NAME)` и одна повторная попытка, лог ERROR.

#### N-62 · LOW · concurrency · client
**Источники:** sync L4 (CONFIRMED).

**Описание.** `cancelTurn` (`SessionDetailStore.kt:1993-2006`) читает и пишет `_session.value` без блокировки — единственный writer вне `sessionMutex` (инвариант 3 в KDoc `:208-215`; locked publish `:1062-1069`). Все writers на `viewModelScope` (Main), и read→write секция `applyDeltaLocked` (`:1023-1069`) без точек suspension, так что interleaving сегодня невозможен; становится реальным, как только любой writer уйдёт с Main (например после N-56). Если ударит — применённые entries перезаписаны при продвинутом `openSeq`, следующая дельта их не перезапросит.

**Исправление (client).** `scope.launch { sessionMutex.withLock { … } }`, как у остальных writers.

#### N-63 · LOW · bug / concurrency · client
**Источники:** sync M3 (CONFIRMED-DOWNGRADED → LOW: требуется reorder ответов, невозможный при serial-сервере).

**Описание.** `selectStream` запускает `fetchInitialPage` на каждый тап и отменяет только `deltaPollJob` (`SessionDetailStore.kt:876-903`); `fetchFullSession` публикует и принимает курсор без проверки stream ответа (`:984-998`); `GetSessionResult` не несёт `selected_stream_id` (`read.rs:770-785`). Но сервер отвечает на запросы одного соединения строго по порядку (`listener.rs:1131-1158`; OkHttp FIFO), так что `get_session` последнего тапа всегда приходит последним; ответ более раннего запроса публикуется под новым выбором на один RTT и перекрывается; `persistCache` stale-окна (`:1143-1155`) коалесцируется 500-мс debounce. Устойчивая порча требует провала позднего запроса (drop сокета между): in-flight падает с `connection closed` (`RemoteClient.kt:869-873`), stale-окно остаётся до дельты `resumeSession` с курсором чужого stream; tail-anchor guard (`SessionEntryMerge.kt:77-81`) ловит только при stale newest index < `total_count − 1`.

**Исправление (client; server — опционально).** Захватывать запрошенный `StreamIdDto`, отбрасывать/не персистить при несовпадении под mutex; отменять предыдущий tab-switch job на новом тапе. Server: эхо `selected_stream_id` на `get_session` сделало бы airtight.

### 4.7 Прочее / LOW (core: очередь, JSON-RPC)

#### N-64 · LOW · concurrency · client
**Источники:** queue L1 (CONFIRMED).

**Описание.** `dispatchQueuedItems` (`QueueController.kt:275-312`) запускает per-item отправку как параллельные `async`-дети без сериализации `tx.send`. На `viewModelScope` (Main) дети идут в порядке создания до первой suspension (после `send`) — FIFO держится на практике. На многопоточном scope (`Dispatchers.Default` по умолчанию `RemoteClient.kt:235` при `connect(scope=null)`) два сообщения могут уйти в любом порядке; сервер обрабатывает по прибытию (`queue.rs:663-712`, `pending_messages.push_back`/merge). Потребителей `queueCall` вне app сегодня нет (`SessionDetailStore.kt:1510`, `:1815`; `:cli` — нет), но станет актуально при N-56 (перевод `RemoteClient` на Default).

**Сценарий отказа.** Только с non-Main scope: офлайн «do X», затем «actually don't» → на проводе «actually don't», «do X».

**Исправление (client).** `Mutex` вокруг шага `send` (ответы по-прежнему ждать параллельно).

#### N-65 · LOW · crash (hardening) · client
**Источники:** queue L2 (CONFIRMED; репро воспроизведён).

**Описание.** `dispatchJsonRpc`: `parsed.jsonObject` (`RemoteClient.kt:1075`) и `idElement.jsonPrimitive` (`:1082`) вне `runCatching`; вызывается из `HandshakeListener.onText` (`:1000`) и `onBinary` (`:956`) без try/catch, адаптер `OkHttpRemoteTransport.kt:56-71` тоже без — исключение улетает в reader loop OkHttp → `onFailure` → reconnect. Кадр валидный JSON, но не объект (массив, строка, `null`), или `id` объект/массив → `IllegalArgumentException`; ответ `-32700` с `"id": null` (JsonNull — `JsonPrimitive`, `.long` падает) тихо отбрасывается — вызывающий ждёт 30 с (или, на fast-path `queueCall`, до смерти сокета). Сегодняшний сервер таких форм не шлёт (`dispatch.rs:88-103`); listener.rs:1038-1057 сервера терпимее клиента. Существующий тест `frame with non-integer id is silently dropped` (`RemoteClientLifecycleTest.kt:1326`) покрывает только string id.

**Верификация.** Репро: `L2 array=IllegalArgumentException: Element class JsonArray … is not a JsonObject objectId=IllegalArgumentException: Element class JsonObject … is not a JsonPrimitive nullId=null`.

**Исправление (client).** Обернуть тело `dispatchJsonRpc` в `runCatching`, отброшенные кадры логировать WARN.

#### N-66 · LOW · concurrency · client
**Источники:** queue L5 (дополнительная находка верификатора; трассировка, не запускалось).

**Описание.** Флашащиеся элементы уже не в `queued` при `close()` (сняты `expireStaleEntries`, `QueueController.kt:213-227`), так что `close()` не завершает их deferred'ы и не удаляет их записи; `close()` отменяет их pending JSON-RPC deferred'ы (`RemoteClient.kt:361-372`), `callInternal` возобновляет ребёнка `CancellationException`, `catch (t: Throwable)` (`:297-309`) превращает её в `Failed(index)`, координатор (запущен на host `scope`, не на `lifecycleJob` — не отменён) восстанавливает элементы в deque мёртвого контроллера (`:328-337`). `awaitWithTtl` (`:349-368`) на стороне caller'а ждёт `item.deferred` остаток TTL (до 24 ч). Дисковые записи переживают (следующий `RemoteClient` их отправит), но когда старый `withTimeout` сработает, он вызовет `queueStore.remove(id)` и `onMessageExpired` → `setBounced` для уже доставленного сообщения; `queueStore` keyed по *текущему* активному серверу — `remove` может попасть в слот другого сервера.

**Сценарий отказа.** Connected, два Send подряд при смене сети (flush идёт), затем правка/смена сервера → `close()`. Сообщения доставлены новым клиентом; ~24 ч спустя те же тексты возвращаются в поле как «bounced».

**Исправление (client).** В `dispatchQueuedItems` перебрасывать `CancellationException`; `close()` дополнительно завершать deferred'ы in-flight элементов (трекать `inFlight` под `stateLock`) `ClosedException` без удаления записей.

#### N-67 · LOW · bug · client
**Источники:** transport L4 (CONFIRMED; репро воспроизведён).

**Описание.** `deferred.invokeOnCompletion` (`RemoteClient.kt:417-423`) срабатывает синхронно, если deferred уже завершён; пути send-failure возобновляют `cont` повторно (`:431`, `:436`). Реальный триггер: `failWebSocket` ставит `failed = true` (так что `send` вернёт `false`) до доставки `onFailure`; если `failPendingOnDisconnect` lifecycle'а ляжет между установкой pending (`:412`) и `sendMaybeCompressed` (`:428`) — двойной resume. Не crash (вызывающий получает исключение), но текст ошибки неверен.

**Верификация.** Репро (фейковый транспорт, чей `send` вызывает `failPendingOnDisconnect` и возвращает `false`): вызывающий получает `IllegalStateException: Already resumed, but proposed with update CompletedExceptionally[java.lang.IllegalStateException: websocket refused frame]` вместо `connection closed: …`.

**Исправление (client).** На путях send-failure завершать *deferred* (`pending.remove(id)?.completeExceptionally(...)`) и позволять единственному handler'у возобновить `cont`.

## 5. Сводная таблица подтверждённых находок

| id | severity | категория | сторона | краткое название | файлы |
|----|----------|-----------|---------|------------------|-------|
| N-01 | HIGH | network-resilience | client | Connected fast-path send теряется при обрыве в окне RTT (нет retry, нет bounce) | QueueController.kt:132-181; RemoteClient.kt:434-437, 871-875; SessionDetailStore.kt:1526-1536 |
| N-02 | HIGH | persistence | client | `close()` удаляет офлайн-очередь с диска (editServer/switch/remove/onCleared) | RemoteClient.kt:344-382; ConnectionManager.kt:296-368, 500; MainViewModel.kt:769-774; SessionDetailStore.kt:1508-1536 |
| N-03 | HIGH | bug | client | Осиротевшие маркеры `PendingSendsRepository` → вечные фантомные «Sending» | SessionDetailStore.kt:604-628, 1113-1130, 1387-1409; PendingSendsRepository.kt:28-35; QueueController.kt:83-95, 228-232, 285-291 |
| N-04 | HIGH | bug (message loss) | client | Отказ отложенной отправки выбрасывает текст и вложения; ошибка глушится 4-с gate | SessionDetailScreen.kt:252-270, 3446-3457; SessionDetailStore.kt:1685-1766, 1827-1836, 1886-1925 |
| N-05 | MEDIUM | network-resilience | both | Replay после mid-RTT drop → дубликат; сервер не дедупит `spk_client_send_id` | QueueController.kt:292-337; sawe: store/queue.rs:548-712, messaging.rs:132-171 |
| N-06 | MEDIUM | persistence / duplication | client | Process death между `queueCall` и ответом: очередь + оживлённый waiter → ложный «upload failed» / дубликат | SessionDetailStore.kt:1616-1651, 1810-1837, 1892; ConnectionManager.kt:358-368; sawe: upload.rs:540-544 |
| N-07 | MEDIUM | network-resilience (UX) | client | Офлайн-пузырь без отмены; bounce после TTL перезаписывает новый черновик | SessionDetailScreen.kt:301-312, 1237-1285, 3126-3131; SessionDetailStore.kt:2270-2273 |
| N-08 | MEDIUM | network-resilience | both | Запрос > 1 MiB — poison pill: сервер рвёт сокет без close, клиент 1-с цикл до 24 ч | RemoteClient.kt:448-455; QueueController.kt:321-337; sawe: listener.rs:696-697, 1008-1010 |
| N-09 | HIGH | network-resilience / stuck UI | client | Неудача первой попытки connect → observer не установлен, UI мёртв, recovery no-op | ConnectionManager.kt:312, 328, 363-373, 417-424; RemoteClient.kt:732-740; AppNavGraph.kt:128-131 |
| N-10 | HIGH | bug (stuck UI) | client | Restore после process death не переподключается | MainActivity.kt:31-37; ConnectionManager.kt:125-137, 524-533; SessionDetailStore.kt:543-548 |
| N-11 | HIGH | bug (stuck UI, wrong message) | client (+server) | Error-envelope на `capabilities` → «server too old» терминальный гейт | ConnectionManager.kt:374-416; JsonRpc.kt:72-75; RemoteDtos.kt:100-122; sawe: listener.rs:1131-1151, zed/main.rs:1704 |
| N-12 | MEDIUM | bug (stuck UI) | client | Workspace без user-driven refresh — пропущенный `onReconnected` = вечный `Loading` | WorkspaceStore.kt:109; MainViewModel.kt:297, 364, 545; WorkspaceScreen.kt |
| N-13 | MEDIUM | network-resilience | client | 10-с handshake-бюджет от enqueue ≈ 4 RTT; сервер даёт 10 с на фазу | RemoteClient.kt:770-793; ConnectFailure.kt:24-27, 137; sawe: listener.rs:659-742 |
| N-14 | MEDIUM | concurrency | client | Lifecycle блокируется в pre-Connected subscribe; drop там = 30 с зависших вызовов | RemoteClient.kt:794-808, 828-841, 856-869 |
| N-15 | MEDIUM | network-resilience | client | Foreground-проба однострайковая с 8 с; error-envelope = «мёртв» | ConnectionManager.kt:163-195, 681; RemoteClient.kt:322-328; SessionDetailStore.kt:682-686 |
| N-16 | MEDIUM | network-resilience | client | Нет `ConnectivityManager`/`NetworkCallback`; восстановление backoff-bounded | AndroidManifest.xml:5-6; RemoteClient.kt:288-292, 322-339; Backoff.kt:31-38 |
| N-17 | MEDIUM | network-resilience | client | TLS-1.3-перехватчик → терминальный pin mismatch без retry | ConnectFailure.kt:151-161; FingerprintPinningTrustManager.kt:71-80; RemoteClient.kt:725-731, 1019-1032 |
| N-18 | MEDIUM | UI truthfulness / recovery | client | `FailedTerminal` = «Нет связи», `onRePair` без вызовов, re-scan QR — no-op | ConnectionBannerText.kt:21-22; ConnectionStatusBanner.kt:94, 174-186; ConnectionManager.kt:225-246, 328 |
| N-19 | MEDIUM | network-efficiency | client | Heartbeat + reconnect-цикл работают в фоне; background edge нет | ConnectionManager.kt:611-614; RemoteClient.kt:687-742; ForegroundEventBus.kt:102-112 |
| N-20 | LOW | hardening | client | `IncompatibleServer` без retry | ConnectionManager.kt:396-415; AppNavGraph.kt:133-148, 306- |
| N-21 | LOW | leak | client | Handshake-таймаут не отменяет OkHttp call (утечка потока+сокета) | RemoteClient.kt:789-817; OkHttpRemoteTransport.kt:110-121; RemoteTransport.kt:23-27 |
| N-22 | LOW | network-efficiency | client | Backoff сбрасывается на каждом handshake → 1-с флаппинг | RemoteClient.kt:708-723; Backoff.kt:6-7 |
| N-23 | LOW | concurrency / leak | client | Синтетический `TransportClosed` без идентичности; брошенный здоровый сокет не закрывается | RemoteClient.kt:329-338, 828-841, 923-927 |
| N-24 | LOW | network-resilience | client | Wake-poke буферизуется через `Reconnecting → Connecting` | RemoteClient.kt:288-292, 699-704 |
| N-25 | LOW | bug | client | `ProtocolError` (retryable) доставляется терминально при AwaitingNonce | RemoteClient.kt:957-964; ConnectFailure.kt:103-109 |
| N-26 | LOW | hardening | client | `onClosing` эхом возвращает код; 1005 бросает на reader-потоке | OkHttpRemoteTransport.kt:72-76 |
| N-27 | LOW | concurrency / cosmetic | client | `removeServer` на IO; nav читает `pairedServers` до удаления | MainViewModel.kt:452-482; ConnectionManager.kt:437-457; AppNavGraph.kt:213-222 |
| N-28 | LOW | cancellation hygiene | client | `runCatching` глотает `CancellationException` (7 сайтов) | RemoteClient.kt:234, 816, 859; ConnectionManager.kt:374; SessionListStore.kt:248, 267; SessionDetailStore.kt:1509; UploadManager.kt:467-500 |
| N-29 | HIGH | network-efficiency / bug | both | Poll-шторм: whole-entry re-send × cancel-and-rearm × недостижимая цель сходимости | SessionDetailStore.kt:707-732, 1187-1191, 1238-1288; RemoteClient.kt:416-426; sawe: read.rs:1202-1251, acp_event.rs:794-867, event_sources.rs:100-119, store.rs:4533-4571 |
| N-30 | HIGH | network-efficiency / OOM | both | Base64-картинки на каждом delta/page, full-res decode в composition, нет lazy fetch | SessionDetailStore.kt:970-977, 1299-1306; RemoteClient.kt:535-579; SessionDetailScreen.kt:1479-1481, 2471-2478; SessionHistoryRepository.kt:246-253; sawe: read.rs:955-956, dto.rs:957-975, upload.rs:495-507 |
| N-31 | MEDIUM | network-efficiency | both | Два keepalive по 30 с (WS ping + `capabilities`); `remote.editor.ping` не в allow-list; два pong'а | OkHttpRemoteTransport.kt:110; ConnectionManager.kt:593-690; sawe: allow_list.rs:19-74, listener.rs:40-43, 1022-1027 |
| N-32 | MEDIUM | network-efficiency / main-thread | both | `list_sessions` целиком на каждый state_changed + main-thread decrypt всех кэшей | SessionListStore.kt:161-203, 463-488; SessionHistoryRepository.kt:169-195; sawe: read.rs:117-181, event_sources.rs:147-153 |
| N-33 | MEDIUM | network-efficiency | client | Первичный транскрипт качается 2–3 раза на открытие | SessionDetailStore.kt:542-687, 1017-1027, 1207-1218, 1353-1357 |
| N-34 | MEDIUM | network-efficiency | server | Нефильтрованный, удвоенный fan-out уведомлений; `editor.subscribe` — no-op реестр | sawe: editor_mcp/notifications.rs:13-23, subscriptions.rs:41-65, allow_list.rs:88-100, event_sources.rs:48-67 |
| N-35 | LOW | CPU | client | Каждый JSON-RPC-ответ парсится дважды на reader-потоке | RemoteClient.kt:1074, 1085 |
| N-36 | LOW | disk | client | `LastSeenIndex` — write-only dead I/O | LastSeenIndex.kt:33, 53; LastSeenRepository.kt:72-79 |
| N-37 | LOW | network-efficiency | server | Избыточные поля: `preview` при `markdown`, `args/result_preview`, `streams[]` на каждом poll | sawe: dto.rs:837-842, 1024-1033; read.rs:782, 1319 |
| N-38 | LOW | docs / tuning | server | Комментарий throttle («2 emits/sec») не соответствует debounce-реализации (~1 / 2 с) | sawe: acp_event.rs:772-830 |
| N-39 | LOW | bug (bytes) | client | `utf8Size` считает суррогатную пару как 5 байт | WireCompression.kt:145-156 |
| N-40 | HIGH | network-resilience | client | `upload_finish`: обрыв / 30-с таймаут / отмена → терминальный `Failed` при идемпотентном сервере | UploadManager.kt:276-293, 657-678; RemoteClient.kt:396-400; sawe: upload.rs:228-257 |
| N-41 | MEDIUM | network-resilience | client | 256 KiB stop-and-wait vs 30-с ack; 5-мин wall-clock deferred timeout | UploadProtocol.kt:35; UploadManager.kt:731-746, 812-873; SessionDetailStore.kt:91, 1703-1741 |
| N-42 | MEDIUM | persistence | client | Process death: URI-грант умер, resume зациклен Paused↔resume | UploadManager.kt:241-267, 614-649, 812-849; SessionDetailScreen.kt:3058-3117; InFlightUploadsRepository.kt:24-33 |
| N-43 | MEDIUM | network-resilience / bug | client | `unknown_upload_id` терминален при наличии файла; upload-state не scoped per server | UploadManager.kt:276-306, 378-391, 487-604; MainViewModel.kt:300-374; sawe: uploads.rs:90-92, 166 |
| N-44 | MEDIUM | bug / efficiency | both | Cap 4/сессия + `forget()` без `upload_abort` + mime не проверяется → слот занят на час | UploadManager.kt:314-370; SessionDetailStore.kt:1904-1906; SessionDetailScreen.kt:3428-3429, 3499; sawe: upload.rs:41, 139-148, 401-416 |
| N-45 | MEDIUM | network-resilience | client | Heartbeat HOL-заблокирован за 256-KiB чанками в едином FIFO OkHttp | ConnectionManager.kt:611-690; OkHttpRemoteTransport.kt:116-122; UploadManager.kt:722 |
| N-46 | MEDIUM | bug | client | Cold-start resume до сокета; `Paused(sent = uploadId)` → «100% (paused)» / «1.6 GB» | ConnectionManager.kt:363-368; MainViewModel.kt:303-322; UploadManager.kt:431-500; SessionDetailScreen.kt:3800-3801 |
| N-47 | LOW | bug | both | Частичный `skip`, overrun при stale `_size`, нет NACK → 45-с циклы | UploadManager.kt:617-621, 706-716; sawe: upload.rs:184-196, listener.rs:1103-1108 |
| N-48 | LOW | UI lie | client | «tap to retry» без retry; Send заблокирован | UploadManager.kt:580, 675; SessionDetailScreen.kt:3181-3189, 3598, 3721-3748 |
| N-49 | LOW | main-thread I/O | client | Decode миниатюры вложения в composition | SessionDetailScreen.kt:3613-3621 |
| N-50 | MEDIUM | network-resilience / concurrency | server | Строго последовательный request loop: RPC/большой кадр блокирует ping, уведомления, idle, остальные RPC | sawe: listener.rs:974-998, 1157, 1161, 1209; proxy.rs:41 |
| N-51 | MEDIUM | bug / security | server | Выключение Remote Control не рвёт живые соединения телефонов | sawe: store.rs:153-166, 261-268; listener.rs:257-281, 395-411, 564-573 |
| N-52 | MEDIUM | network-resilience / bug | server | Очередь уведомлений прокси роняет *новейший* кадр (doc: oldest) — теряет ack и dirty | sawe: proxy.rs:42-48, 255-279 |
| N-53 | LOW | leak | server | Zombie-соединения: idle перевзводится outbound, заблокированный send не видит kick | sawe: listener.rs:540-558, 621, 985, 994, 1209 |
| N-54 | LOW | network-resilience | server | Ban ladder по /24 ловит CGNAT; reason при revoke вводит в заблуждение | sawe: listener.rs:224-240, 354-364, 449-512, 1232-1236 |
| N-55 | LOW | network-resilience | server | Reject чанка молчит; TTL от создания; resolve абортит после одной отправки | sawe: listener.rs:1101-1122; upload.rs:54, 179-197, 280-295, 539-544 |
| N-56 | MEDIUM | main-thread I/O | client | Encrypted-prefs I/O на Main: 4 открытия на старте, Keystore на первом Send, fsync под `stateLock`, decrypt на открытии чата, whole-file rewrite кэша | MainActivity.kt:31-32; ConnectionManager.kt:129-142, 357-367, 524-533; SessionDetailStore.kt:553, 1089-1091, 1473-1484; QueueController.kt:151-167; EncryptedQueueStore.kt:89-96, 227-245; SessionHistoryRepository.kt:67-122, 197-215; PendingSendsRepository.kt:73-93 |
| N-57 | MEDIUM | silent failure | client | `sendError` SharedFlow без коллектора на Workspace/Settings — ошибки теряются | MainViewModel.kt:150-151, 395-405; SessionListStore.kt:616-657; ConnectionManager.kt:264-278; WorkspaceScreen.kt; SettingsScreen.kt |
| N-58 | LOW | crash | client | `rememberSaveable` неограниченного черновика → `TransactionTooLargeException` | SessionDetailScreen.kt:3123, 3305-3318; NewSessionDialog.kt:88-89 |
| N-59 | LOW | crash (hardening) | client | Коллизия ключа `date:` при шаге часов десктопа | SessionDetailScreen.kt:834-846, 1036-1062; ChatTimeline.kt:26-33 |
| N-60 | LOW | cosmetic | client | Crash-log I/O в composition; grace баннера сбрасывается на rotation; QR-дедуп ошибок | CrashLogsScreen.kt:62, 135, 187; SettingsScreen.kt:177; ConnectionStatusBanner.kt:103-106; QrPairingScreen.kt:87-122 |
| N-61 | LOW | persistence | client | Потеря Keystore-keyset не самолечится — персистентность тихо выключена навсегда | AppMasterKey.kt:32-44; EncryptedQueueStore.kt:63-81; PendingSendsRepository.kt:73-86 |
| N-62 | LOW | concurrency | client | `cancelTurn` пишет `_session` вне `sessionMutex` | SessionDetailStore.kt:1993-2006 |
| N-63 | LOW | concurrency | client | Гонка переключения stream-tab (только при провале позднего запроса) | SessionDetailStore.kt:876-903, 984-998, 1143-1155 |
| N-64 | LOW | concurrency | client | FIFO на проводе зависит от однопоточности dispatcher'а | QueueController.kt:275-312 |
| N-65 | LOW | crash (hardening) | client | `dispatchJsonRpc` бросает на не-объектном кадре / не-примитивном `id` | RemoteClient.kt:1075, 1082; OkHttpRemoteTransport.kt:56-71 |
| N-66 | LOW | concurrency | client | `close()` во время flush паркует caller'ов на весь TTL, потом bounce доставленного | QueueController.kt:213-227, 297-309, 328-368; RemoteClient.kt:361-372 |
| N-67 | LOW | bug | client | Двойной resume в `callInternal` → «Already resumed» вместо причины | RemoteClient.kt:416-438, 871-875 |

**Итого:** HIGH 10 · MEDIUM 27 · LOW 30 = 67 записей (после слияния 91 исходного подтверждённого пункта из 14 файлов).

## 6. Рекомендуемый порядок исправлений

Принцип: сначала максимум устойчивости за минимум изменений (только клиент, без изменения wire-контракта), затем трафик (частично требует синхронного релиза), затем hardening.

### Волна 1 — устойчивость (client-only, без изменения контракта)
1. **N-09 + N-12 + N-18 + N-20:** `startObservingConnectionState` до `connect()`; `UiState` из наблюдаемого `ConnectionState`; schema-gate на каждом rising edge; guards `:312/:328` учитывают `FailedTerminal`, `addServer` с `force = true` при смене URL; pull-to-refresh/retry на Workspace; отдельный баннер + `onRePair` для `FailedTerminal`; Retry на `IncompatibleServer`.
2. **N-10:** авто-connect из ViewModel независимо от `savedInstanceState`; текст «reconnecting…» вместо «pair a server».
3. **N-11:** гейт только по декодированному `CapabilitiesDto`; error-envelope = транзиентный отказ пробы.
4. **N-02 + N-28 + N-66:** `close()` не удаляет per-server записи с диска; rethrow `CancellationException` во всех 7 сайтах; `SessionDetailStore` не удаляет маркер на отмене, bounce на `ClosedException`.
5. **N-01 + N-04 + N-07:** bounce текста в черновик при любом не-TTL отказе (текстовый и deferred путь); сохранять `AttachmentRef`'ы вместо `forget()`; deferred-ошибки мимо 4-с gate; live-доставка bounce с дописыванием к черновику; отмена queued-пузыря.
6. **N-03:** материализовать только маркеры с csid в `EncryptedQueueStore`; `enqueued_at` + GC; удаление маркера на терминальных путях очереди.
7. **N-40 + N-46 + N-43 + N-48:** `upload_finish` → `Paused(total,total)` на транспортных ошибках; `resumeAllFromDisk` из первого `onReconnected`; `unknown_upload_id` → re-init с 0; реальный retry на `Failed`.
8. **N-14 + N-15:** `failPendingOnDisconnect` из listener'а напрямую; foreground-проба — 2 strike / ≥15 с, error-envelope = alive.
9. **N-29 (клиентская половина):** `has_more == false` = converged, floor-delay; single-flight poll с флагом re-poll (снимает и большую часть серверного HOL из N-50).
10. **N-57:** app-level `SnackbarHost` + `Channel`/`replay=1` для `sendError`.

### Волна 2 — трафик и батарея
1. **N-30:** client — downscale перед upload, `include_images=false` на poll/page, lazy `get_session_entry`, decode вне Main с `inSampleSize`, кэш миниатюр. Server (**wire, gate по версии схемы**) — default `include_images=false` для `get_session_changes`.
2. **N-29 (серверная половина, wire, синхронный релиз):** per-stream `seq` в `dirty`; append-форма дельты / `known_hash`; `mod_seq` только при throttled emit; без `preview` при `markdown` (N-37).
3. **N-31:** client — убрать/удлинить RPC-heartbeat, error-envelope ≠ dead. Server (additive, **сначала сервер**) — `remote.editor.ping` в allow-list с ответом в `dispatch`, `Message::Ping(_) => None`.
4. **N-32 + N-34 (server additive):** `state`/`title` в payload `state_changed`/`title_changed`; фильтрация подписок в `proxy.rs`, коалесцирование `dirty`. Client: debounce refresh, `count`, `prune` на IO.
5. **N-19 + N-16:** background edge → пауза heartbeat/цикла; `NetworkCallback` → `wakeReconnect`/`forceReconnect("network changed")`.
6. **N-13 + N-21:** таймер handshake от `onOpen`, `cancel()` в `RemoteTransport`, конечный `callTimeout`.
7. **N-33, N-41, N-45:** single-flight initial load; progress-based deferred timeout, адаптивный чанк; `queueSize()`-gate и ack как liveness.
8. **N-56:** encrypted prefs на IO eagerly, `RemoteClient` на Default-scope (потребует N-64 mutex), кэш per-session с cap.
9. LOW-эффективность: N-35, N-36, N-38, N-39.

### Волна 3 — hardening и серверный контракт
1. **N-50 + N-52 + N-53 (server):** reader/writer split, spawn per dispatch, drop-oldest/коалесцирующая очередь уведомлений, `TCP_USER_TIMEOUT`, idle только по inbound.
2. **N-05 (server additive) + N-06:** csid-LRU с `{duplicate: true}`; пометка pending-send `queued` после enqueue. После этого — безопасный re-queue на транспортных ошибках в N-01.
3. **N-51 (server):** kill всех слотов при выключении Remote Control.
4. **N-08 (both):** клиентский cap + per-item counter; серверный `Close(1009)`.
5. **N-44 + N-47 + N-55 (both):** `upload_abort` из `forget()`, mime при выборе; `upload_chunk_rejected`, идемпотентный re-ack, TTL от последнего чанка; loop `skip`, clamp read.
6. **N-42:** копия файла в `cacheDir` при выборе; `SecurityException` терминален.
7. **N-17, N-22, N-23, N-24, N-25, N-26, N-27, N-54, N-58 … N-65, N-67:** по списку, каждое — локальная правка.

### Что требует синхронного релиза клиента и сервера (wire contract)
- Append-форма дельты / `known_hash` / per-stream seq в `dirty` (N-29): новые поля additive, но выигрыш только когда обе стороны обновлены; клиент должен уметь работать со старым сервером (fallback на whole-entry).
- Смена default `include_images` для `get_session_changes` (N-30): только под gate `wire_schema_version`, иначе старые сборки телефона теряют картинки.
- `remote.editor.ping` (N-31): сервер первым; клиент переключает heartbeat только при наличии метода (иначе `-32601`).
- `{duplicate: true}` на повтор csid (N-05): additive, клиент уже стабилен по csid — можно выкатывать сервер первым.
- `state` в `agent_session_state_changed` (N-32), `upload_chunk_rejected` (N-55), `Close(1009)` (N-08), reason «server shutting down» (N-51): additive, порядок релиза свободный.
- Всё остальное — client-only или server-only без изменения формата кадров.

## 7. Приложения

### A. Отклонённые и пониженные находки

**Отклонённые (REJECTED) — не входят в основной список:**

| Исходный id | Название | Причина отклонения |
|-------------|----------|--------------------|
| transport L3 | `close()` во время `onConnected()` оставляет `connectionState == Connected` | `close()` постит `UserClose` до отмены deferred/job; `awaitDisconnect` находит его в буфере без suspension, цикл `break`, `:743` пишет `Disconnected`. Репро прошёл: `rightAfterClose=Disconnected`, `Connected` после close не наблюдается; сценарий «cancel scope без close()» не встречается (`tearDownConnection` всегда вызывает `close()`). |
| sync L1 | Catch-up poll до завершения re-subscribe; subscribe дважды | `onConnected()` awaited-replay `remote.editor.subscribe` **до** `Connected` (`RemoteClient.kt:795-807, 856-867`); `onReconnected` срабатывает после. Второй subscribe в `restartNotificationsObserverAndAwait` — safety net при тихом провале первого, не «gap». |
| sync L3 | Full-reload fallback обгоняет новую дельту, откатывая курсор | Требует reorder ответов; сервер отвечает строго по порядку (`listener.rs:1131-1158`), in-flight через reconnect падает, а не приходит поздно. Остаток — design note: любой full-reload сбрасывает `loadOlder`-страницы и уводит читателя в хвост (merge вместо replace остаётся полезным). |
| ui L3 | `sendMessageBlocks` тихо роняет отправку при `activeClient()==null`/`openSessionId==null` | Compose bar включён только при `UiData.Loaded` (`SessionDetailScreen.kt:511-512, 3188-3189`), которое появляется после `openSession` с уже выставленным `openSessionId`; rebind идёт при некомпозированном чате. Состояние «bar включён и guard срабатывает» построить не удалось; `emitError` в early-return — только hardening. |

Частично отклонено: ui L2 — класс `csid:` (коллизия seed `currentTimeMillis`) отвергнут (нужно совпадение старта процессов в ms-окне; replay с потерянным ответом не существует); класс `date:` оставлен как N-59.

**Пониженные (CONFIRMED-DOWNGRADED):**

| Исходный id | Было → стало | Причина |
|-------------|--------------|---------|
| transport M1 | MEDIUM → LOW (N-21) | Утечка потока+сокета реальна; «насыщение 5-per-host dispatcher'а» неверно — OkHttp не считает WS-вызовы в per-host лимит (`Dispatcher.kt:189-193`); stale-попытки не держат аутентифицированный сокет. |
| transport M4 | MEDIUM → LOW (N-22) | Задокументированное поведение; единственный конкретный триггер (два устройства с одним именем) не лечится cap'ом; батарея, не stuck. |
| transport L6 | LOW, сужено (N-26) | Зарезервированные коды не доходят до `onClosing` (reader бросает раньше); реально только 1005; 1012 принимается. |
| sync H2 | HIGH → MEDIUM (в N-29) | Throttle сервера — перевзводимый debounce (~1 poke / 2 с, не 2/с); порог starvation ≈ 1.8 с RTT, а не 300 мс; в целевом диапазоне 1–5 с всё ещё голодает. |
| sync M3 | MEDIUM → LOW (N-63) | Нужен reorder ответов; сервер serial; устойчивая порча только при провале позднего запроса. |
| ui L2 | LOW, сужено (N-59) | См. выше. |
| queue L4 | LOW, сужено (N-61) | `allowBackup="false"` исключает backup-restore триггер. |

**Повышенные (CONFIRMED-UPGRADED):** upload L1 → MEDIUM (N-46: срабатывает на каждом холодном старте с персистентной загрузкой, а не только на редком edge); queue M2 — severity оставлена MEDIUM, но цикл хуже описанного (1-секундный из-за сброса `attempt`, N-08).

**Коррекции описаний без изменения severity:** transport M3 (состояние `Connecting`, а не ложный `Connected`), transport M8 (класс триггеров — только TLS-1.3-перехватчики на кастомном порту), queue H3 (сценарий 1 уже), queue M3 (маркер — `apply()`, fsync только у `EncryptedQueueStore`), connmgr H1 (workspace не грузится вообще; выходы: editServer с изменённым транспортом, remove+re-pair), connmgr L1 (clobber до следующего switch, не «кратко»), sync H1 (tail-resync ограничивает шторм ≤ 4 с в типичном длинном ходе; неограничен для коротких ходов/Idle-dirty/после рестарта), sync M2 (decode только orphans), sync M4 (~30 rewrites/min), upload H1 (текстовый путь тоже bounce только по TTL), ui H1 (чат показывает Error, не спиннер; multi-server имеет скрытый выход через Servers), ui H3 (Back на API 31+ не финиширует activity), ui M4 (`remember(images)` content-equality), ui M5 (теряется новый черновик, не bounce), ui L4 (номера строк), server H1 (0.5–2 emits/s диапазон), server Q1 (tungstenite 0.28, не 0.20.1).

### B. Методология и покрытие

**Процесс.** Ревьюеры работали по `REVIEW_PROMPT.md` (приоритеты: сетевая устойчивость/эффективность → баги; шкала HIGH/MEDIUM/LOW; правило «читать код, не документацию; кросс-чек сервера для каждого protocol-level утверждения; конкретный failure scenario или downgrade»). Верификаторы работали по `VERIFY_PROMPT.md`: перечитать каждую цитируемую строку, грепнуть call sites/dispatcher/lock, построить сценарий end-to-end, при возможности — временный репро-тест в `core/src/test/kotlin/ru/sipaha/sawe/core/AuditRepro_<id>Test.kt` (только `:core`; запуск `./gradlew -q :core:test --tests …`; файл удалён, `git status --porcelain` пуст). Gradle не запускался ревьюерами (параллельная базовая сборка). Верификаторы connmgr/sync/upload/ui/server — только трассировка (`:app`/server-находки не воспроизводимы на `:core`-стенде). OkHttp проверялся по исходникам 5.3.0 из `~/.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp-jvm/5.3.0/…-sources.jar` (`RealWebSocket.kt`, `Dispatcher.kt`, `RealCall.kt`, `ConnectPlan.kt`, `WebSocketProtocol.kt`, `WebSocketReader.kt`, `WebSocketWriter.kt`), tungstenite — 0.28.0 из `~/.cargo/registry` (`protocol/mod.rs`).

**Прочитано полностью (объединение секций Coverage).**
- `core/src/main/kotlin/ru/sipaha/sawe/core/`: `RemoteClient.kt`, `OkHttpRemoteTransport.kt`, `RemoteTransport.kt`, `Backoff.kt`, `ConnectFailure.kt`, `ConnectionState.kt`, `HmacChallengeAuth.kt`, `FingerprintPinningTrustManager.kt`, `JsonRpc.kt`, `ConnectionBannerText.kt`, `QueueController.kt`, `QueueStore.kt`, `WireCompression.kt`, `WireDictionary.kt`, `PairingUrl.kt`, `UserMessageBlocks.kt`, `UploadProtocol.kt`, `SessionEntryMerge.kt`, `ChatTimeline.kt`, `OptimisticVisibility.kt`, `ContextFill.kt`.
- `app/src/main/kotlin/ru/sipaha/sawe/app/`: `MainActivity.kt`, `SpkApplication.kt`, `ui/App.kt`, `ui/nav/AppNavGraph.kt`, `ui/solutions/SessionDetailScreen.kt` (все 4384 строки), `NewSessionDialog.kt`, `ProjectPicker.kt`, `SolutionProjectsScreen.kt`, `StatePill.kt`, `ui/workspace/WorkspaceScreen.kt`, `ClosedSolutionsPickerSheet.kt`, `CreateSolutionDialog.kt`, `ui/servers/ServersListScreen.kt`, `ui/settings/SettingsScreen.kt`, `EditServerDialog.kt`, `CrashLogsScreen.kt`, `ui/qr/QrPairingScreen.kt`, `ui/common/ConnectionStatusBanner.kt`, `ui/theme/Theme.kt`, `diagnostics/CrashLogger.kt`; `vm/ConnectionManager.kt`, `ConnectionLifecycle.kt`, `ConnectionContext.kt`, `ForegroundEventBus.kt`, `SingleFlightRefresh.kt`, `WorkspaceClientImpl.kt`, `MainViewModel.kt`, `RpcDecoding.kt`, `SessionDetailStore.kt`, `SessionListStore.kt`, `LastSeenIndex.kt`, `WorkspaceStore.kt`, `CatalogStore.kt`, `UploadManager.kt`, `PickedAttachment.kt`; `data/EncryptedQueueStore.kt`, `PendingSendsRepository.kt`, `DraftRepository.kt`, `AppMasterKey.kt`, `PairingRepository.kt`, `PairedServer.kt`, `NavStateRepository.kt`, `AttachmentDraftRepository.kt`, `InFlightUploadsRepository.kt`, `SessionHistoryRepository.kt`, `CachedSessionHistory.kt`, `ListCacheRepository.kt`, `LastSeenRepository.kt`; `AndroidManifest.xml`, `proguard-rules.pro`.
- Сервер: `sawe/crates/remote_control/src/{listener,proxy,wire_codec,wire_dict,allow_list,dispatch,settings,model,remote_control,auth(part),cert}.rs`; `sawe/crates/solution_agent/src/{event_sources.rs (non-test), stream.rs, upload.rs (manager part)}`, `mcp/{read,dto,uploads}.rs`; `sawe/crates/editor_mcp/src/{notifications,subscriptions}.rs`, `tools/capabilities.rs`.

**Просмотрено выборочно (skimmed):** `sawe/crates/solution_agent/src/store.rs` (state/queue/subagent watermark, `mutate_state`, emit sites), `store/acp_event.rs` (throttle, `mod_seq`), `store/queue.rs:495-730`, `store/hydration.rs`, `store/teammate_reconciler.rs`, `model.rs` (`change_seq` seeding, `rebuild_streams`), `mcp/messaging.rs`, `metrics_emitter.rs`, `solution_agent.rs:190-245`, `context_server/src/listener.rs:340-615`, `remote_control/src/store.rs:153-290`, `editor_mcp/src/tools/subscribe.rs`, `zed/src/main.rs` (порядок init); `acp_thread/src/acp_thread.rs:245-278`; мобильные тесты — только по именам (`RemoteClientLifecycleTest`, `SafetyNetPollTest`).

**Не покрыто:** `:cli` smoke-клиент; Roborazzi/unit-тесты по содержанию; `sawe/crates/remote_control/src/cert.rs` внутренности и `store.rs` persistence-пути; `auth.rs` за пределами контракта; `editor_mcp` producers `workspace.*`/`workspace_seq`; `mcp/cold_cache.rs`; `store/queue.rs` merge-семантика в деталях; `QrPairingScreen` camera lifecycle; `CatalogStore`/`ListCacheRepository` main-thread чтения (кроме `prune`); внутренности `editor_mcp::emit_notification` fan-out за пределами `notifications.rs`.

### C. Репро-тесты

Все тесты — временные классы на `FakeRemoteTransport` + `StandardTestDispatcher`/`TestScope` + `InMemoryQueueStore` + `BackoffStrategy.fixed`/`Default`, удалены после прогона. «Воспроизведено» = тест упал в задуманном месте (репро-утверждение/`error("… REPRODUCED")`); «прошёл» = баг не воспроизведён.

**`AuditRepro_TransportTest` (7 тестов, 5 упали = воспроизведены, 2 прошли):**

| Находка | Сценарий | Вывод |
|---------|----------|-------|
| M3 → N-14 | subscribe на conn1, drop, reconnect, handshake OK, fail транспорта до ответа replay | `stateWhileAwaitingSubscribe=Connecting`, `stateRightAfterDrop=Connecting`, `stateAfter1s=Connecting`; вызов приложения жив через 1 с, падает на +30 с с `TimeoutCancellationException`; первый `Reconnecting` на t0+30000; `connectedAfterDrop=[]`. **Воспроизведено.** |
| L2 → N-23 | `failFromServer` на живом транспорте (событие в очереди, state `Connected`), `forceReconnect` (grace обойдён через reflection на `lastConnectedAtMs`), reconnect, handshake OK | немедленно `Reconnecting(attempt=1, lastFailure=Unreachable("watchdog"))` на t=150, третий транспорт на t=250; `transports=3`, `tx2.closed=false`. **Воспроизведено.** |
| L3 (отклонена) | `close()` во время awaited subscribe | `stateBeforeClose=Connecting`, `rightAfterClose=Disconnected`, `afterSettle=Disconnected`, `final=Disconnected`; `Connected` после close не наблюдался. **Прошёл — не воспроизведено.** |
| M4 → N-22 | `BackoffStrategy.Default`, 4× (server close 1001 → reconnect → handshake) | `Reconnecting(attempt=1, 1000 ms)` каждый раз; 5 handshake за 4.0 с virtual. **Прошёл — задокументированное поведение продемонстрировано.** |
| L4 → N-67 | фейковый транспорт, чей `send` вызывает `failPendingOnDisconnect` (reflection) и возвращает `false` | вызывающий получает `IllegalStateException: Already resumed, but proposed with update CompletedExceptionally[java.lang.IllegalStateException: websocket refused frame]` вместо `connection closed: …`. **Воспроизведено.** |
| L5 → N-24 | poke точно на истечении backoff (после timer task, до возобновления тела цикла) | `stateAtWake=Reconnecting(attempt=1)`; попытка 2 падает; третий транспорт в то же virtual-время: `transportsAtEdge=3` (ожидалось 2), `t=1000`. **Воспроизведено.** |
| L7 → N-25 | первый кадр `{"type":"challenge","challenge":"0011","v":2}` | `FailedTerminal(ProtocolError(...))`, `connect()` падает с `ConnectException`, retry нет. **Воспроизведено.** |

**`AuditRepro_QueueTest` (8 кейсов, 8/8 упали на `error("… REPRODUCED")` = все воспроизведены):**

| Находка | Сценарий | stdout |
|---------|----------|--------|
| H1 → N-01 | Connected → `queueCall` → кадр на tx1 → `closeFromServer` до ответа → reconnect | `H1 result=Failure(java.lang.IllegalStateException: connection closed: Server closed the connection (code 1000): nat rebinding) store=0 expired=0` / `H1 resentOnTx2=false tx2.sent=1` |
| H1b → N-01 | транспорт отказывает кадру в Connected | `result=Failure(java.lang.IllegalStateException: websocket refused frame) store=0 state=Connected` |
| H2 → N-02 | drop → `queueCall` офлайн → store size 1 → `close()` | `H2 result=Failure(ClosedException: client closed before flush) storeAfterClose=0 expired=0` |
| H2b → N-02 | предзаполненная запись store, `connect()` в ожидании handshake, `close()` | `storeAfterClose=0 expired=0` — сирота удалена без caller'а и bounce |
| M1 → N-05 | офлайн-очередь → reconnect → кадр на tx2 → drop до ответа → reconnect | `M1 firstSend=1 storeAfterDrop=1` / `M1 secondSend=1` — те же params (тот же csid) дважды |
| M2 → N-08 | компрессия согласована, `queueCall` 3 MiB hex-строки в Connected | `M2 fast-path frame sizes: binary=1791913 text=129` |
| L2 → N-65 | кадры: JSON-массив, объект с `id`-объектом, `-32700` с `id: null` | `L2 array=IllegalArgumentException: Element class JsonArray … is not a JsonObject objectId=IllegalArgumentException: Element class JsonObject … is not a JsonPrimitive nullId=null` |
| L3 → N-39 | reflection на `utf8Size` с суррогатной парой | `L3 counted=5 actual=4` |

**Без репро-тестов** (трассировка кода): connmgr, sync, upload, ui — `:app`-находки, недоступные `:core`-стенду; server — Rust, cargo не запускался. Для `queue H3` (N-03) верификатор отдельно отметил: «не воспроизводимо из `:core`, только трассировка».

---

## 8. Статус исправления — 2026-09-06

Все 67 подтверждённых находок разобраны. Работа велась параллельными агентами по слоям с
непересекающимся владением файлами (`:core` → репозитории → vm → ui, плюс серверная сторона),
затем каждый слой прошёл адверсариальное ревью, и находки ревью были исправлены отдельным
проходом. Ничего не закоммичено — правки лежат в рабочем дереве обоих репозиториев.

### Что изменилось по числам

| | было | стало |
|---|---|---|
| Тесты `:core` | 332 | 380 |
| Тесты `:app` | 41 | 337 |
| Тесты `remote_control` (lib + e2e) | 60 | 81 |
| Файлов изменено, клиент | — | 43 |
| Файлов изменено, сервер | — | 10 |

`solution_agent --lib` — 797 passed. `cargo clippy` и `cargo fmt --check` чистые.
Полный принудительный прогон (`--rerun-tasks`) в одиночку: 380 + 337, ноль падений.

### Wire-контракт

**Схема не бампалась, версия по-прежнему 6.** Единственное изменение формата — новое
опциональное поле `image_count` на `EntrySummary`, аддитивное в обе стороны: старый клиент
игнорирует неизвестный ключ (`ignoreUnknownKeys = true`), новый клиент читает отсутствие как
«неизвестно — опрашивать как раньше», а не как ноль. Всё остальное — client-only или
server-only без изменения кадров. Совместимость со старым сервером проверена вживую (см. ниже).

### Ключевые исправления

- **Жизненный цикл (N-09…N-12, N-15, N-16, N-19, N-20).** Наблюдатель состояния ставится до
  `connect()`, `UiState` выводится из наблюдаемого состояния, schema-gate требует положительного
  доказательства (`wire_schema_version` в `structuredContent`), а не «оно распарсилось».
  Добавлены `ConnectivityManager`, парковка reconnect-лестницы в фоне с 60-секундной отсрочкой и
  проверкой пустой очереди, двухстрайковая проба живости, авто-подключение после смерти процесса.
- **Потеря сообщений (N-01…N-04, N-06, N-07).** `RemoteClient.close()` больше не удаляет
  неотправленное с диска — это передача эстафеты следующему клиенту. Введена типизированная
  таксономия отказов (`StillQueued` / `NotQueued` / `TransportLost` / `MessageRejected` /
  `FrameTooLarge` / `FrameRefused` / `QueueCancelled`), по которой клиент решает, возвращать ли
  текст в черновик; двойной bounce исключён. Добавлена отмена сообщения, стоящего в очереди, с
  честным отказом, если кадр уже ушёл в транспорт.
- **Трафик (N-29…N-34).** Цикл сходимости больше не целится в недостижимый глобальный
  `change_seq`: признак схождения — `has_more == false`; poll стал single-flight и не отменяет
  сам себя; картинки не запрашиваются на опросах и подтягиваются лениво по одной, а `image_count`
  снимает опрос с текстовых сообщений; base64 не попадает в дисковый кэш; `list_sessions`
  дебаунсится.
- **Загрузка вложений (N-40…N-48).** Транзиентные ошибки перестали быть терминальными, размер
  чанка и ack-таймаут адаптивны, запись чанков ограничена глубиной очереди OkHttp, файл
  копируется в приватное хранилище атомарно (через `.part` + `rename`), появился реальный retry.
- **Сервер (N-50…N-55, N-08, N-38).** Разделение чтения и записи в `listener.rs` с диспетчеризацией
  вне цикла чтения, коалесцирующая очередь уведомлений с вытеснением самого старого, idle-таймер
  только по входящим кадрам, close-код 1009 на превышение размера, уведомление об отклонённом
  чанке, TTL загрузки от последней активности.

### Найдено и исправлено уже в ходе ревью правок

Ревью поймало пять регрессий, внесённых самими исправлениями. Стоит знать, потому что две из них
были опаснее исходных находок:

1. **Порядок запросов на сервере.** Первая версия разделения цикла отправляла каждый запрос в
   отдельную задачу, и LIFO-слот планировщика tokio инвертировал порядок практически всегда.
   Два сообщения, отправленные подряд из офлайн-очереди, могли лечь в транскрипт в обратном
   порядке — необратимая порча. Исправлено: запись в апстрим осталась в цикле чтения в порядке
   провода, в задачу вынесено только ожидание ответа.
2. **Тайм-аут записи 30 с на кадр** убивал здоровых клиентов на слабом канале — ровно тот
   сценарий, ради которого затевался аудит. Бюджет теперь растёт с размером ответа.
3. **Сборка мусора маркеров** удаляла 100% записей с вложениями при каждом холодном старте,
   превращая всю персистентность отложенной отправки в мёртвый код.
4. **Восстановление keyset'а** удаляло файл настроек при любой первой ошибке, включая временную
   недоступность Android Keystore, при которой данные целы.
5. **Гонка при копировании файла** во временное хранилище: отмена могла удалить файл, который
   копирование затем создавало заново. Найдена через «плавающий» тест, который оказался не
   flaky-тестом, а индикатором настоящего бага.

### Сознательно не сделано

- **Серверная половина N-29** (append-форма дельты / `known_hash`, `mod_seq` только по
  throttled-emit) и **смена значения по умолчанию `include_images`** (N-30) — требуют
  согласованного релиза обеих сторон. Клиентская половина уже даёт основной выигрыш.
- **N-37** (не слать `preview` при наличии `markdown`) — меняет форму существующего поля.
- **N-05** (серверный dedupe по `spk_client_send_id`) — без него любой автоматический retry
  отправки небезопасен, поэтому клиент сознательно не ретраит неоднозначные отказы, а возвращает
  текст в черновик.
- **N-34** (фильтрация подписок по сессиям) — риск потерять уведомление, на которое клиент
  неявно рассчитывает; нужна отдельная проверка.
- **Carve-out парковки при активной загрузке** (CM-3): `UploadManager` не отдаёт признак
  занятости, парковка учитывает только очередь.

### Как проверено

Помимо 717 юнит-тестов — живой прогон на headless-эмуляторе против настоящего редактора,
запущенного в изолированном runtime-каталоге (чтобы не трогать конфигурацию пользователя):

1. Сопряжение по URL, TLS-пиннинг и HMAC — приложение открывает Workspace.
2. **Против старого серверного бинарника** (собранного до правок) — подключается штатно, то есть
   совместимость не сломана.
3. Редактор убит → баннер «Переподключение… (попытка N)», содержимое экрана сохраняется, на
   экран сопряжения не выбрасывает.
4. Редактор поднят → автоматическое восстановление, баннер исчезает.
5. Процесс приложения убит и запущен заново → сам подключается к сохранённому серверу.
6. Всё то же повторено против пересобранного сервера с новой логикой цикла.

Ни одного падения (`FATAL` / `AndroidRuntime`) ни на одном шаге.

---

## 9. Отложенное на совместный релиз — сделано 2026-09-07

Четыре пункта из раздела 8 («Сознательно не сделано») и два из «требует отдельной проверки»
разобраны. Два из них по итогам проектирования **отклонены с обоснованием** — это результат, а не
пропуск.

### Главное решение: версию схемы не бампали

Гейт совместимости на клиенте — проверка на **равенство** в обе стороны, и её вердикт ведёт на
терминальный экран с разрывом соединения. Значит, бамп 6 → 7 мгновенно превратил бы каждый уже
установленный телефон в «сервер несовместим». Ни одно из изменений не является ломающим, поэтому
версия осталась **6**, а согласование вынесено в список фич.

`editor.capabilities` теперь отдаёт `wire_features` (всегда, даже пустой — чтобы отличать «фич нет»
от «согласования нет»), `server_instance_id` и `csid_dedupe_window_ms`. Токены:
`entry_body_delta`, `omit_preview`, `csid_dedupe`, `quiet_message_appended`. Клиент не отправляет
ни одного нового параметра, пока соответствующий токен не пришёл на **текущем** соединении;
структуры параметров на сервере объявлены с `deny_unknown_fields`, поэтому «попробовать и
посмотреть» не работает — отсюда и явное согласование.

Добавлена константа `MIN_SUPPORTED_WIRE_SCHEMA_VERSION` (сегодня равна 6, поведение не меняется) —
чтобы следующий реальный разрыв совместимости мог расширить диапазон вниз, не повторяя ловушку с
равенством.

### Что сделано

- **N-29 (серверная половина).** Клиент присылает `known_entries` — что у него уже есть (длина в
  **байтах UTF-8** + первые 16 байт SHA-256 в hex), сервер отвечает `markdown_prefix_len` +
  `markdown_tail` вместо целого тела. При проектировании выяснилось, что приём не сработал бы вовсе:
  сервер оборачивает тело в `"## Assistant\n\n…\n\n"`, завершающий перенос строки *сдвигается* по
  мере роста, и удержанные байты перестают быть префиксом. Клиент теперь предлагает тело без
  завершающих пробельных символов.
- **N-37.** Параметр `omit_preview_when_markdown` — 200-символьный `preview` больше не едет рядом с
  полным `markdown`. Только для записей не от пользователя: оптимистичные пузыри без идентификатора
  отправки сопоставляются именно по `preview`.
- **N-05.** Идемпотентность по `spk_client_send_id` с окном 24 ч и полем `delivery` в ответе. Это
  разблокировало безопасный повтор отправки с неоднозначной доставкой вместо возврата текста в
  черновик — но только при совпадении `server_instance_id`, поскольку таблица живёт в памяти
  процесса.
- **Узкая часть N-34.** Дублирующее уведомление `agent_session_message_appended` можно погасить через
  `suppress_kinds` у `editor.subscribe`; подавление — по соединению, в прокси, где известен клиент.
  Отгружено только потому, что грепом доказано: у полезной нагрузки нет ни одного читателя, кроме
  одного обработчика, делающего ровно то же, что и `agent_session_dirty`.

### Что отклонено и почему

- **N-30 (смена значения по умолчанию `include_images`).** Клиент теперь передаёт параметр явно во
  всех вызовах, так что для него это no-op. Зато смена умолчания молча лишила бы картинок сборки до
  2026-09-06 и любых сторонних потребителей — ровно тот «тихо выключающий фичу» случай, который
  контракт запрещает.
- **N-34 в полном объёме (фильтрация по сессиям).** Клиент вообще не публикует область видимости;
  часть потребляемых событий кросс-сессионна по устройству (создание сессии, изменения состояния
  непросматриваемых сессий, событие дочерней сессии, маршрутизируемое в родительскую); а реестр
  подписок — глобальный, а не по соединению, так что фильтр одного клиента применился бы ко всем.
  Потерянный «dirty» — это навсегда застрявший транскрипт.

### Найдено при сверке двух реализаций

Реализации писались параллельно по общей спецификации, затем сверялись построчно. Чек-лист
побайтового соответствия чистый по всем девяти пунктам, но всплыли четыре дефекта:

1. **Заявка на идентификатор отправки не освобождалась**, если что-то падало между заявкой и
   постановкой в очередь. Повтор такой отправки получал ответ «дубликат», клиент считал это успехом —
   и сообщение исчезало без следа. Раньше тот же сценарий громко падал. Исправлено; тест проверен
   негативным контролем.
2. **Неизвестное значение `delivery` роняло разбор всего ответа**, превращая принятую сервером
   отправку в ошибку с возвратом текста. Это был единственный enum на проводе без варианта
   `Unknown`, который есть у всех его соседей.
3. **`skip_serializing_if` на `preview`** мог опустить ключ, который уже установленные клиенты
   объявляют обязательным: одна такая запись роняет разбор всего ответа, а не «рисует пусто», как
   утверждала спецификация. Сегодня недостижимо, но ничем не защищено — добавлен тест с
   исчерпывающим `match`, чтобы новый вариант не компилировался без доказательства.
4. **Проверка склейки по длине структурно бессильна**: сервер всегда выставляет
   `markdown_len == prefix_len + tail.len()`, поэтому любая база подходящей длины проходит
   арифметику. Клиент теперь переcчитывает хэш базы перед склейкой.

### Числа и проверка

| | до этого этапа | после |
|---|---|---|
| Тесты `:core` | 380 | 432 |
| Тесты `:app` | 337 | 392 |
| `solution_agent --lib` | 797 | 826 |
| `remote_control` | 85 | 85 |
| `editor_mcp` | 79 | 79 |

clippy и `fmt --check` чистые. Живая проверка на эмуляторе против настоящего редактора: сервер
объявляет все четыре фичи при `wire_schema_version` 6; телефон сопрягается с нуля и открывает
Workspace; при принудительно опустошённом списке фич клиент работает штатно и **ни одного**
`invalid_params` на проводе — деградация подтверждена в поле, а не только тестами.

### Остаётся открытым

Ничего: последний пункт — межпроцессный повтор после перезапуска редактора — закрыт 2026-09-08,
см. раздел 10.

## 10. Межпроцессный повтор после перезапуска редактора — 2026-09-08

Последний отложенный пункт раздела 9. `QueueController` поднимает долговременную очередь с диска на
старте процесса и на ближайшем фронте `Connected` отправлял **всё** безусловно. Запись удаляется
только по успешному ответу, поэтому после kill'а процесса запись может описывать сообщение, кадр
которого **уже был записан** и которое редактор, возможно, уже применил. Повтор такой записи
дублирует сообщение пользователя.

Дедупликация по `spk_client_send_id` (N-05) поглощает повтор, но её таблица живёт в памяти
редактора: повтор бесплатен только пока это **тот же** процесс (`server_instance_id`) и заявка ещё
внутри `csid_dedupe_window_ms`. Для внутрипроцессного пути эта политика уже была —
`canRequeueAmbiguousSend` / `SendRetryContext` в `SessionDetailStore`. У межпроцессного пути гейта
не было вовсе.

### Что сделано

- **Провенанс на записи очереди.** `QueuedMessage` получил необязательное
  `attempt: QueuedSendAttempt?` (`at_ms` + `instance_id`). `QueueController.dispatchOne` пишет его
  **до** кадра (write-ahead), поэтому смерть процесса в окне «кадр ушёл — ответа нет» не теряет сам
  факт отправки. Инстанс берётся из **неблокирующего** снимка `serverFeatures` — штамп обязан
  называть того, кому кадр реально пишется, и не имеет права тормозить flush; до ответа пробы
  `capabilities` это даёт `instance_id = null`, что позже читается как «повтор не доказан», то есть
  в консервативную сторону. Повторный штамп пропускается, если инстанс не изменился:
  `EncryptedQueueStore.add` переписывает весь шифрованный blob, и цикл реконнект-ретраев иначе молотил
  бы диск впустую.
- **Отсутствие `attempt` — это разрешение.** Запись, которую никогда не отдавали в транспорт, — это
  первая доставка, а не повтор. Именно это сохраняет работу офлайн-очереди через kill процесса;
  ошибка здесь возвращала бы набранный офлайн текст в поле ввода при каждом холодном старте. Так же
  декодируются blob'ы старых сборок (`ignoreUnknownKeys = true` в обоих хранилищах), и это верное
  для них прочтение.
- **Гейт.** Записи, поднятые с диска и уже имеющие `attempt`, проходят через предикат `:app`
  `canReplayRehydratedSend`, прокинутый в `RemoteClient`/`QueueController` параметром `replayGate`
  (`null` = разрешать, чтобы `:cli`, тесты `:core` и любой не-Android потребитель вели себя как
  раньше). Предикат — тонкий адаптер поверх `canRequeueAmbiguousSend`: контекст момента отправки
  восстанавливается из записи (`instance_id` ненулевой только когда тот пир объявлял `csid_dedupe`;
  окно берётся у живого пира, что законно ровно потому, что проверка инстанса уже доказала тот же
  процесс). Два пути повтора не могут разъехаться. Отказ → `ReplayNotSafeException`, запись
  удаляется, `onMessageExpired` возвращает текст в черновик — та же процедура, что и у
  `abandonOnClose`.
- **Ожидание фич — одно на flush.** `onConnected` выгружает очередь сразу после `subscribe`, а проба
  `capabilities` — обычный вызов из `:app`, ответ которого к этому моменту обычно ещё не пришёл;
  синхронное чтение `serverFeatures` увидело бы `NONE` и запретило бы всё. Поэтому вердикт берётся
  через ограниченное `awaitDedupeFeatures` — **один раз на flush**, и только если в нём есть что
  гейтить: последовательное ожидание по элементу стоило бы N × 30 с против пира без дедупликации.
  Константа `REPLAY_GATE_TIMEOUT_MS` = 30 с, тот же бюджет, что у `REPLAY_RENEGOTIATE_TIMEOUT_MS`.
- **Записи этого процесса не гейтятся — сознательно.** Кадр ушёл, сокет умер, вызывающий ещё жив и
  смотрит на пузырь: этой неоднозначностью владеет `dispatchSendWithRetry` (см. ветку
  `TransportLostException` в `dispatchOne`). После рестарта вызывающего нет, пузыря нет, и
  установленное восстановление — возврат в черновик.
- **`PersistedPendingSend.serverInstanceId` удалён.** Поле писалось «на будущее» ровно под этот
  гейт, но гейт читает запись **очереди**: только `:core` знает, в какой процесс кадр реально ушёл,
  а маркер знал инстанс на момент тапа — он отличается всякий раз, когда сообщение пережидает
  реконнект в очереди, и вовсе отсутствует у записей старых сборок. Маркеры с ключом
  `server_instance_id` на диске декодируются по-прежнему (`ignoreUnknownKeys = true`).

### `QueuedCall` перестал быть `data class`

Штамп провенанса переписывает `message` посреди диспатча, а тот же объект лежит в `ArrayDeque` и в
`LinkedHashSet` in-flight, откуда удаляется **по значению**. Сгенерированный `hashCode` по
изменяемому полю перевёл бы элемент в другую корзину сразу после штампа — и он никогда не удалился
бы из множества, а вызывающий ждал бы свой `Deferred` весь TTL. Теперь это обычный класс с
identity-равенством и `@Volatile var message`; всем обращениям этого достаточно — каждое передаёт
ровно тот экземпляр, который получило.

### Числа

| | до | после |
|---|---|---|
| Тесты `:core` | 432 | 444 |
| Тесты `:app` | 392 | 400 |

Новые тесты: `core/src/test/.../QueueControllerReplayGateTest.kt` (12) — «не отдавали в транспорт»
проходит без обращения к гейту, отказ гейта = ничего на проводе + запись удалена + ровно один
bounce + `ReplayNotSafeException`, разрешение = обычная отправка, свои записи не гейтятся даже после
потери транспорта и повторного flush'а, write-ahead штамп и отсутствие лишней перезаписи при том же
инстансе, отсутствие ожидания там, где гейтить нечего, FIFO при отказе в середине очереди,
совместимость blob'а без ключа `attempt`; `app/src/test/.../ReplayRehydratedSendTest.kt` (8) —
таблица истинности предиката.
