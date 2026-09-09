# Член Solution — это не проект каталога (2026-09-09)

## Симптом

Пользователь создаёт проект с телефона и получает **две** ошибки подряд:

```
Couldn't create project: Field 'catalog_id' is required for type with serial name
'ru.sipaha.sawe.core.AddEmptyMemberResult', but it was missing

Field 'catalog_id' is required for type with serial name
'ru.sipaha.sawe.core.SolutionMember', but it was missing
```

Экран «Projects» после этого остаётся на второй ошибке насовсем.

Важно: **проект на сервере при этом создавался**. В логе редактора видно и сам каталог, и созданную
следом сессию:

```
09:18:54 INFO [git::repository] opening git repository at ".../something-new/new-project/.git"
09:19:00 INFO [solution_agent::store] creating session in solution=SolutionId(19) ...
```

То есть RPC проходил, ломался только разбор ответа на клиенте.

## Причина

Сервер 2026-07-13 (`a81166f241`, «solutions: replace slug identities with surrogate counter ids»)
развёл две сущности, которые до того назывались одинаково:

| | сервер | что это |
|---|---|---|
| `MemberDetail.id` | `i64`, всегда | идентификатор **члена** решения |
| `MemberDetail.origin_catalog_id` | `Option<i64>`, `skip_serializing_if` | из какой строки каталога склонирован |
| `AddEmptyMemberResult.member_id` | `i64` | id нового члена |
| `RemoveMemberParams.member_id` | `i64`, `deny_unknown_fields` | что удалять |

Клиент этот переезд не отследил. Коммит `c58d88a` (2026-07-14, на следующий день) мигрировал id со
`String` на `Long`, но **имя ключа оставил прежним** — миграция была про тип, не про переименование.

Отсюда три поломки сразу:

1. `SolutionMember.catalog_id` — обязательное поле, которого на проводе нет вообще. Значит **первый
   же член любого решения делает `solutions.get` неразбираемым навсегда**. Пустое решение
   (`members: []`) разбиралось нормально — поэтому баг и не замечали.
2. `AddEmptyMemberResult.catalog_id` — сервер отвечает `member_id`.
3. `removeMember` слал `{solution_id, catalog_id}`, а `RemoveMemberParams` — это
   `deny_unknown_fields` над одним `member_id`. То есть **удаление проекта с телефона не работало
   вовсе**, отдельной жалобы на это просто не поступало.

Плюс следствие в UI: `SolutionProjectsScreen.displayName` искал имя члена в каталоге и падал в
`catalogId.toString()`. У пустого проекта строки каталога нет **по определению** (серверный тест
`add_empty_member_does_not_add_catalog_row`), так что даже после починки разбора он рисовался бы
голым числом.

## Исправление (только клиент)

Сервер не трогали: его модель непротиворечива, а `catalog_id: null` для члена вернул бы ровно ту
двусмысленность, которую июльская миграция убирала.

- `SolutionMember` → `id` + `name` + `local_path` + `origin_catalog_id: Long? = null` + `status`.
- `AddEmptyMemberResult` → `member_id`.
- `RemoteClient.removeMember(memberId)` — один параметр, без `solution_id`.
- `CatalogStore.reconcileMemberAdds` снимает ghost-строки по `originCatalogId` (ghost-строки заведены
  по каталожному id, и погасить их может только член, пришедший из каталога).
- `SolutionProjectsScreen` берёт имя из `member.name`, а ключ списка и удаление — из `member.memberId`.

`solutions.add_member` и уведомления `solution_member_add_*` **остались на `catalog_id`** — там это
по-прежнему верно: клонируют именно проект каталога.

## Что теперь это ловит

- `RemoteDtosTest` — разбор: клонированный член, пустой член без `origin_catalog_id`, и явная
  проверка, что `catalog_id` на члене игнорируется.
- `SolutionMemberWireTest` (новый) — то направление, которое ни один декодер не проверит: что именно
  клиент **кладёт** в кадр. `remove_member` — только `member_id`; `add_member` — по-прежнему
  `catalog_id`.
- `LiveSolutionMemberIntegrationTest` (новый, `@Tag("integration")`) — полный цикл против **живого**
  редактора: создать решение → создать пустой проект → прочитать список членов → удалить проект →
  удалить решение. Именно такой тест ловит расхождение контракта; юнит-тесты по обе стороны при этом
  оставались зелёными три месяца.

## Как поднять стенд для интеграционного теста

Пары можно чеканить без GUI: слушатель `remote_control` стартует по FS-watcher'у на переходе
`enabled` false→true, поэтому достаточно записать `remote-control.json` **до** запуска редактора.

```bash
RT=/tmp/sawe-pair-test; CFG=$RT/.spk/sawe-dev/config; PORT=21781   # НЕ 21772 — там живой редактор
mkdir -p "$CFG"
# {"server_address":null,"server_port":PORT,"enabled":true,
#  "clients":[{"name":"emutest","secret_base64":<32 случайных байта в STANDARD base64>,
#              "created_at":"...Z"}]}  chmod 600
sawe/script/run-mcp --debug --headless --runtime-dir "$RT" &
# ждём появления remote-control.cert.der и LISTEN на PORT
# URL: sawe-remote://<host>:<PORT>?secret=<секрет в URL_SAFE_NO_PAD>&client=<имя>
#      &server_fp=<sha256(cert.der) в URL_SAFE_NO_PAD>
SPK_EDITOR_PAIRING_URL="$(cat $RT/pairing-url.txt)" ./gradlew :core:test -DincludeTags=integration
```

Тот же URL (с host `10.0.2.2`) вводится в эмуляторе через «Enter URL manually» —
`adb shell "input text '<кусок>'"` кусками по 16 символов; целиком одной командой строка обрывается.
Deep-link'а для `sawe-remote://` в манифесте нет, а пары лежат в Tink-хранилище, так что подложить
пару файлом нельзя — только через экран.

## Проверено

- `:core` 470 тестов, `:app` 459, падений нет.
- Интеграционный тест — зелёный против изолированного headless-редактора.
- Живьём в UI на эмуляторе: проект создаётся, строка показывает **имя** (`my-empty-proj`), удаление
  проекта проходит и список становится пустым. Скриншоты снимались в `/tmp/navshots/`.
- Форма ответов сервера сверена с **живым** редактором пользователя (`solutions.get` для решения
  с пустым членом и для решения с клонированными).
