# Отчёт о ходе реализации шага К15 (contract-service)

Сессия: Qoder Desktop, адаптер `QWEN.md` (qwen), подписи «Qwen4 Max». Репозиторий: `dmitriydubov/qwen-rvl-online` (другие репозитории не затрагивались — запрет владельца).
Ветка задачи: `task/k15-contract-service-optional-field-types`, PR #1 (https://github.com/dmitriydubov/qwen-rvl-online/pull/1), база `origin/main`.
Версии набора по ходу сессии: Gauntlet 4.5 (база `ba1aee4e`) → 4.6 (`c1eb10f6`) → 4.7 (`203fb21a`) → 4.7.1 (`0eae7b31`); все merge — коммитами в ветке задачи.
Время в отчёте — **UTC**; локальное время машины было UTC+1 (видно в `git log %cI` и логах Lima). Точность anchors: коммиты — до секунды (`git log --all --format='%h %cI %s'`), прогоны — mtime логов и строки `BUILD … in …`; моменты прихода сообщений владельца/координатора не логируются — интервалы с ними помечены «≈» (±5–10 мин).

Коммиты ветки задачи (UTC):
| Коммит | Время (UTC) | Сообщение |
|---|---|---|
| `a40e7b96` | 25.11 14:12:00 | spec: нестроковое значение необязательного текстового, GUID- или датового поля… |
| `70b29b41` | 25.11 14:54:23 | реализация + тесты + docs. By coder. |
| `45eae75c` | 25.11 21:33:55 | закалка приёмки (строгий путь, whitelist полей). By hardener. |
| `5a135442` | 25.11 22:08:00 | spec: разделить входные и ожидаемые поля ошибки сценария 4. By specifier. |
| `fbd94ed0` | 25.11 22:51:52 | merge origin/main (Gauntlet 4.6) |
| `2cc0ecbc` | 25.11 23:23:02 | завершающие LF по находке ревью. By coder. |
| `82aa6aae` | 26.11 06:06:58 | merge origin/main (Gauntlet 4.7). By coder. |
| `6c4c658d` | 26.11 08:10:19 | merge origin/main (Gauntlet 4.7.1). By coder. |
| `1db1f5b7` | 26.11 09:42:52 | spec: контроль утечки читает individual_request_number (вариант A). By specifier. |

PR открыт 26.11 11:21:04 UTC. Финальный `gauntlet` — BUILD SUCCESSFUL in 1h 36m 7s, лог `contract-service/build/gauntlet-final-3.log` (mtime 26.11 11:19:24 UTC), `stop_gate.py --verify-ran gauntlet` → 0.

## 1. Понимание задачи и границы

**Цель.** Привести `OptionalField.text()`, `uuid()`, `date()` к единому правилу с `OptionalField.longInteger()` и `JsonFields.hasArray()`: `null` только когда ключа нет в JSON или поле равно JSON `null` (`field.isAbsent()`); любой нестроковый узел — `ContractViolationException.invalidValue(field.path())` без значения узла (ПДн) в тексте и без cause. Наблюдаемо: сообщение `FAILED` с первой попытки, `error_message = "недопустимое значение поля <полный путь с data.>"`, счётчик `contracts_inbox_failed_total{event_type}`, записи не создаются/не меняются, ДГ-03 не публикуется.

**В области изменений (проверено чтением кода, не только по промпту):**
- `service/inbox/parse/OptionalField.java` — единственный класс src/main; `JsonField`, `JsonFields`, `FieldPresence` не менялись (правило уже живёт в `JsonField.text()/absentOrInvalid()`).
- ДГ-01 `ContractTaskUpserted` — 8 необязательных полей `ParticipantParser.java:17,26-28,32-34`: `program_id` (uuid), `personal_data.middle_name/relation_type/address` (text), `personal_data.document.series/issued_by/division_code` (text), `issued_at` (date).
- ДГ-02 `ContractTaskCancelled` — `data.cancel_comment` (`TaskCancellationParser.java:31`).
- ДГ-07 `LmPolicyIssued` — `data.policy_usk_number` только в ветке ISSUED (`UskResultParser.java:43-47` через `LmPolicyTexts.LAYOUT`/`FieldPresence.OPTIONAL.textOf`); ветка ERROR (`:36-39`) поле не читает.

**Вне фичи/вне задачи (с причинами):** конверты `TaskEnvelopeParser` и HTTP `/internal/v1` — не используют `OptionalField`; события оплаты Ordering `PaymentOrderEvent` — правило Д-09 (журнал :345-351), запись и пропуск без FAILED, закреплено «Вне фичи» по решению владельца 3; пустые строки `""`/`"   "` — строки (`isString()==true`), поведение не меняется (решение 2, строка E таблицы); `longInteger()`/`hasArray()` уже по правилу; ДГ-05 `KmContractRegistered` — оба компонента REQUIRED; замороженные фичи прошлых шагов и README модуля не правились.

**Граничные случаи, выявленные сверх текста промпта:**
- `JsonField.date()` на невалидной **строке** бросает `invalidValue(path, cause)` с `DateTimeParseException` — существующее поведение сохранено; cause отсутствует только у нестроковых узлов. В тестах это разделено (`hasNoCause()` для нестроковых, `hasCauseInstanceOf(DateTimeParseException)` для строки «Тестов»).
- `program_id = null` у NONE-участника (без выпуска) — штатно, без PROGRAM_REQUIRED; доменная ошибка срабатывает только у участника с выпуском (строки A2 против A1).
- Разбор идёт до бизнес-транзакции (`prepare` до `complete`) — при нарушении не применяется **ни один** участник, включая соседнего валидного; это закреплено отдельными утверждениями в сценарии 1.
- Разница префиксов пути: `ContractViolationException` из `JsonFields` всегда `data.participants[i]…`, доменные `ParticipantDataException` — `participants[i]…` без `data.` (в сценарии 1 kontrol доменных ошибок пути без префикса).
- **Дефект собственной спецификации, найденный гейтом, а не промптом:** в сценарии 4 колонки «код ошибки»/«текст ошибки» подставлялись и в шаг публикации, и в таблицу ожидания — мутация меняла обе стороны согласованно (4 неубиваемых мутанта). Исправлено только после явного делегирования владельца (`5a135442`).
- **Класс мутантов «перенаправление пути»:** мутация цифры `participants[0]→participants[1]` в колонке «путь строки» даёт другой валидный неутёкший путь — шаг утечки его не различает (m136/m54). Закрыто вариантом A по решению владельца (`1db1f5b7`).

## 2. Чем руководствовался при проектировании и реализации

**Реально открытые и сверенные документы (file:line):**
- `QWEN.md` целиком (адаптер; после merge 4.6 — обновлённый раздел 0 про IDE-клиенты без хуков и самонастройку среды).
- `gauntlet/CLAUDE.md` целиком: процесс (шаги 1–8), гейты, жёсткие правила 1–15, «Правила владельца», «Рабочий контур» (обе команды проверки следов), «Источники документации».
- `contract-service/CLAUDE.md` целиком: предметная область, «Как пишем код» (комментариев нет, лимиты, SOLID), «Персональные данные», «Тесты», структура пакетов.
- `gauntlet/subagents.md` целиком: правило тщательности, приёмка (раздел 2), карточки 4.1–4.13, маршрут (раздел 5), обязательства адаптера (раздел 6).
- Карточки ролей: `.claude/agents/specifier.md`, `.claude/agents/hardener.md`, `.claude/agents/reviewer.md` + обёртки `.qwen/agents/*.md`.
- «Точки интеграций сервис Договоров и Заявок — редакция 2.md» (`project_files/contract-service/edited/`): :121 (нарушение контракта отклоняется сразу), :124 (бизнес-ошибка не сбой), :213–226 (поля участника, условная обязательность program_id/relation_type), :230 (значение не того типа — нарушение контракта целиком), :232 (выпущенная запись не меняется), :346 (cancel_comment string Нет), :742/:749 (policy_usk_number, обработка ISSUED/ERROR).
- «Журнал решений Договоров.md»: Д-09 :310–311, :345–351; Д-10 :381–384 (базовое правило) и **:433–439** (изменение владельца 2026-10-17 — сюда specifier сначала ошибся, возврат за доказательствами).
- `contract-service/docs/design.md:353–362` (старый абзац — переписан), `docs/operations.md:505–527` (SQL возврата), `docs/troubleshooting.md:262–306` («Входящее отклонено окончательно»), `CHANGELOG.md` (секция [Не выпущено]).
- Конфиги: `gauntlet/config/gauntlet.properties`, `gauntlet/config/contract-service/gauntlet.properties`, `architecture.properties`, `checkstyle.xml` (ParameterNumber max=4 — только main sources), `gauntlet/gauntlet.gradle` (задачи гейтов, область checkstyle/pmd — main; gherkinMutants scope; verified.sha256 в 4.7).
- Код-опоры: `OptionalField.java`, `JsonField.java`, `JsonFields.java`, `FieldPresence.java`, `ParticipantParser.java`, `TaskCancellationParser.java`, `UskResultParser.java`, `LmPolicyTexts.java`, `ContractViolationException.java`, `MessageBodies.java`, тесты и билдеры (`OptionalFieldTest`, `FieldPresenceTest`, `ContractTaskParserTest`, `TaskCancellationParserTest`, `UskResultParserTest`, `TaskBodies`, `LmPolicyResultBodies`), стенд (`TaskMessages`, `OneCMessages`, `TaskPublishingSteps`, `OneCPublishingSteps`, `InboxSteps`, `ServiceLogSteps`, `MessageSender`, `PostgresTestcontainer`, `RabbitTestcontainer`, `StandDatabase`, `StandStorage`).

**Официальные источники версий из сборки (правило 12):**
- Версии: `./gradlew gauntletInfo` (Java 21, Gradle 8.14.3, Boot 4.0.7, JUnit 6.0.3, Cucumber 8.0.1, PIT 1.22.1) и `./gradlew dependencies --configuration runtimeClasspath` (`build/deps-runtime.log:265` — `tools.jackson.core:jackson-databind:3.1.4`).
- Jackson 3.1.4: javadoc-jar с repo.maven.apache.org — `isNull()` «check if this node was created from JSON literal null value», `isString()` «checks whether this node represents JSON String value», `path(String)` «…a "missing node" … will be returned»; байткод `javap -c`: `isString()` = `getNodeType()==STRING`; `JsonNodeFactory.stringNode` (замена deprecated `textNode`).
- AssertJ 3.27.7: `javap` разрешённого jar — `hasNoCause()`, `hasMessage(String)`.
- Gherkin 42.0.1: синтаксис фичи проверен парсером из jar кэша Gradle (jshell): ENVELOPES=47, PARSE_ERRORS=0, PICKLES=45 — 4 раза (после создания, после правки Д-10, после колонок сценария 4, после варианта A).
- Testcontainers 2.0.5: байткод `DockerClientFactory.getRemoteDockerUnixSocketPath()` и стратегий (порядок env/properties, unix-путь из `docker.host`) — для настройки среды.

**Варианты реализации (правило 14):**
1. **Guard `isAbsent()` + делегирование `JsonField` (выбран).** `text/uuid/date`: `if (field.isAbsent()) return null; return field.text()/uuid()/date();`. Правило «нестроковый → invalidValue» остаётся в одном месте (`JsonField.absentOrInvalid`), дублирования нет, CC каждого метода 2, мёртвый `isText()` удалён. Зеркалит `longInteger()`.
2. Тернарник с проверкой `isText()` и броском в else — отклонён: дублирует правило JsonField в OptionalField, больше ветвлений, два места изменения при будущем пересмотре контракта.
3. Обобщённый приватный helper `<T> T read(Supplier<T>)` — отклонён: абстракция с одной реализацией без второго кандидата запрещена правилом модуля («понятность»), усложняет чтение record.

**Варианты стенда приёмки:** словарь видов узла — (а) switch в шагах (CC > 3, дубли в двух step-классах), (б) per-step литералы (дубли), (в) **общий `FieldNodes` с Map.kind→JSON-сниппет + строгий проход по пути (выбран)**: одно место, CC ≤ 3, переиспользуется ДГ-01/ДГ-02/ДГ-07. Новые шаги — 4 формулировки, совместимые с существующими (проверено grep по 244 аннотациям и алиасам `MessageSender`).

## 3. Сабагенты

Инструмент: `Agent` (`subagent_type: general-purpose`, **foreground**, `run_in_background: false`). Причина: Qoder Desktop не регистрирует кастомные типы `.qwen/agents/*` — роль передавалась заданием «прочитай карточку роли первой и следуй буквально» (`.claude/agents/<role>.md` + `.qwen/agents/<role>.md`). Форк не использовался (запрещён), worktree не создавались (одна задача — одна ветка).

| # | Роль | Цель | Итог | Принят | Проверка по 4.5 |
|---|---|---|---|---|---|
| 1 | specifier | черновик .feature + таблица решений + дыра-чек | отчёт 5/5, 45 строк Examples, файл создан | со 2-й попытки (1 возврат) | открыл ТИ :121/:124/:213–226/:230/:232/:346/:742/:749 и журнал :310–311/:345–351 — цитаты дословны; **нашёл расхождение: Д-10 cited :381–384, изменение на :433–439** → возврат; grep step-определений и `MessageSender` aliases; повторно прогнал jshell-парсер (47/0/45); `javap` isString |
| 2 | specifier (доработка) | точечная правка шапки [Д-10]/[ТИ-07] | правка внесена, сценарии не тронуты | с 1-й | прочитал шапку на диске (:17–21, :38–41), `git status` — только фича; diff только комментарии шапки |
| 3 | hardener #1 | mutationGate + gherkinMutation, закалка | mutationGate 14/14; gherkin 4.5: 2 прогона красные (дефекты набора), 16 выживших; правки шагов сделаны; **прерван владельцем** на 3-м прогоне (~21:31 UTC) | правки приняты главным агентом, итогового отчёта нет (прерван) | сверил `mutation-gate-before.log` (PIT summary 14/14); diff `ServiceLogSteps`/`TaskPublishingSteps` сопоставил с 16 строками `MUTANT SURVIVED` (12 закрываются); коммит `45eae75c` |
| 4 | reviewer #1 | состязательное ревью 18 файлов | CHANGES REQUESTED: 1 minor (3 файла без LF); гейты: прогон 1 красный (среда, порт MinIO), прогон 2 зелёный | с 1-й | сам повторил воспроизведение: `git diff … | grep -c 'No newline'` → 3, `tail -c 1 | od` → `}`/`}`/`|`, `.editorconfig:8`; M=18 совпал с моей командой; spot-check `reviewer-gauntlet-fast-2.log` BUILD SUCCESSFUL |
| 5 | reviewer #2 | проверка исправления LF | APPROVED, 18/18, новых находок нет | с 1-й | `git diff fbd94ed0..HEAD --name-only` = 3 файла; `grep -c 'No newline'` → 0; байтовая идентичность содержимого (проверка ревьюера) spot-check; `reviewer-round2.log` 13m6s SUCCESSFUL |
| 6 | hardener #2 (после merge 4.7) | финальная закалка на 4.7 | mutationGate 14/14 (`mutation-gate-before.log` mtime 06:14 UTC); gherkinMutation 4.7 дал **47/47 фич** (дефект GlueImpact, `gherkin-mutation-47.log`, 3931 мутант); **прерван владельцем** (~07:31 UTC), итог не вернулся | не принят (прерван) | логи осмотрены; после хотфикса 4.7.1 финальные гейты прогнал главный агент |

**Шаги без штатного сабагента — честно:**
- Финальные `mutationGate`/`gherkinMutation`/`gauntlet` и приёмка закалки выполнялись **главным агентом**: запуск hardener #2 отклонён владельцем, координатор затем явно указал «продолжи финал до PR»; SubagentStop-хук в Qoder Desktop не загружен, обязанность прогона mutationGate после hardener лежит на главном агенте (`gauntlet/subagents.md` раздел 6).
- Настройка среды (Docker/Lima, общие контейнеры, `~/.testcontainers.properties`, tmpfiles в VM) — главный агент; обновлённый `QWEN.md` (4.6) прямо предписывает самонастройку без остановки на вопрос.
- Возврат specifier #2 — отдельный запуск, не продолжение (среда не умеет continue сабагента); это доработка отчёта, не новый раунд работы.

## 4. Проведённые проверки и гейты (хронология, UTC)

| Время (UTC) | Прогон/лог | Длит. | Вердикт | Падения и исправления |
|---|---|---|---|---|
| 25.11 12:39 | `gauntletInfo` / `deps-runtime.log` | 12s | OK | версии зафиксированы |
| 25.11 14:17 | focused unit, `tdd-red.log` | ~40s | **красный (ожидаемо, TDD)** | 130 тестов, 56 падений — «Expecting code to raise a throwable» на нестроковых узлах (старый код возвращал null) |
| 25.11 14:18 | focused unit, `tdd-green.log` | ~40s | OK | реализация guard+делегат; 130/130 |
| 25.11 14:19 | `compileAcceptanceTestJava` | ~1s | OK | warning deprecated `textNode` → заменён на `stringNode` (javap Jackson 3.1.4) |
| 25.11 14:21–14:35 | gauntletFast #1, `gauntlet-fast.log` | 14m16s | **FAILED** | 29 IT: `RootlessKit PortManager.AddPort(): bind: address already in use` (десятки контейнеров postgres на контекст); 2 приёмки: каскад от неудачного `StandStorage.recover` (тот же порт). Исправление: общие k15-postgres/k15-rabbit через `test.postgres.url`/`test.rabbitmq.url` (штатный режим модуля) |
| 25.11 14:36–14:50 | gauntletFast #2, `gauntlet-fast-2.log` | 13m56s | **FAILED** | 1 IT `SeveralInstancesLeadershipTest`: `FATAL: sorry, too many clients already` (max_connections=100). Исправление: контейнер пересоздан с `-c max_connections=500`. Приёмка 528/528 зелёная |
| 25.11 14:50–14:52 | gauntletFast #3, `gauntlet-fast-3.log` | 1m53s | **SUCCESSFUL** | 2950/0/0 (XML-отчёты), census 335/335, `--verify-ran gauntletFast` → 0 |
| 25.11 ~14:57 | mutationGate (hardener #1), `mutation-gate-before.log` | ~2m | OK | PIT 14/14 KILLED, 0 SURVIVED/NO_COVERAGE/RUN_ERROR |
| 25.11 15:05–17:33 | gherkinMutation 4.5 #1, `gherkin-mutation-before.log` | 2h27m25s | **FAILED** | 16 выживших (все в новой фиче) + 4711 `MUTANT ERROR` («not executed», «failed outside a step», срыв порядка cN/zN) — системные дефекты 4.5 (heap 512m → OOM ~3927-го сценария → второй JVM; срыв кадра NDJSON). Диагностика координатора, исправлено в 4.6 |
| 25.11 17:35–20:17 | gherkinMutation 4.5 #2, `gherkin-mutation-run2.log` | 2h41m28s | **FAILED** | те же ошибки харнеса; hardener правил шаги между прогонами |
| 25.11 20:18–21:31 | gherkinMutation 4.5 #3, `gherkin-mutation.log` | ~73m | **прерван** | владелец отменил сабагента; orphan-прогон остановлен главным агентом (`pkill GradleDaemon`) |
| 25.11 21:34 | gauntletFast #4 + mutationGate, `gauntlet-fast-4.log` | 7s | OK | после коммита `45eae75c`; PIT 14/14; приёмка UP-TO-DATE (входы не менялись с зелёного прогона hardener) |
| 25.11 22:52–23:12 | reviewer gauntletFast #1, `reviewer-gauntlet-fast.log` | ~13m | **FAILED** | `MinioCertificateStoreIT` — порт rootless (среда, класс вне изменения) |
| 25.11 23:15–23:17 | reviewer gauntletFast #2, `reviewer-gauntlet-fast-2.log` | 1m52s | OK | 2950/0/0, 528/528 |
| 25.11 23:24–23:37 | reviewer round 2, `reviewer-round2.log` | 13m6s | OK | приёмка перезапущена после LF-правки фичи; 528/528 |
| 25.11 23:39–26.11 02:34 | **полный gauntlet 4.6**, `gauntlet-final.log` | 2h55m45s | **FAILED** | gherkinMutation: 3931 мутант — 3930 killed, **1 survived (m136)**, 0 errors, 1056/1056 контролей; остальные гейты зелёные. m136 вынесен владельцу (варианты A/B) |
| 26.11 06:14 | mutationGate (hardener #2) | ~2m | OK | PIT 14/14 |
| 26.11 06:15–07:31 | gherkinMutation 4.7, `gherkin-mutation-47.log` | ~76m | **прерван** | GlueImpact 4.7 вернул 47/47 фич (3931 мутант) — дефект, хотфикс 4.7.1; владелец прервал |
| 26.11 08:10–09:36 | **полный gauntlet 4.7.1 (до A)**, `gauntlet-final-2.log` | 1h25m34s | **FAILED** | область 20/47; 2287 — 2286 killed, **1 survived (m54 = m136)**, 0 errors, 536/536 контролей |
| 26.11 09:43–11:19 | **полный gauntlet 4.7.1 (после A)**, `gauntlet-final-3.log` | 1h36m7s | **SUCCESSFUL** | **2287/2287 killed, 0 survived, 0 errors; 536/536 контролей**; PIT 14/14; unit 2950/0/0; приёмка 528/528; census 335/335+1/1; CRAP OK (6 методов); DRY 0; `--verify-ran gauntlet` → 0 |
| 25.11 ~21:40, 26.11 ~06:0x, ~11:20 | проверки следов (обе команды «Рабочий контур») | <1s | **пусто ×3** | новых следов нет, как на базе |
| 25.11 13:0x, 13:1x, 22:0x, 26.11 09:4x | jshell Gherkin parse ×4 | ~5s | 47/0/45 | синтаксис после каждой правки фичи |
| 25.11 13:5x–14:0x | Testcontainers smoke (jshell) ×3 | ~30s | OK / FAILED / OK | без override после ребута VM — ryuk 500 (mount host-пути); с override — OK; доказательство механизма |

## 5. Честный самоаудит: обходы гейтов и срабатывания защиты

**guard.py / хуки набора: срабатываний не было — механизм не был активен.** Сессия шла в Qoder Desktop, который не исполняет хуки `.qwen/settings.json` (SessionStart не дал строку `[gauntlet] active`, `printenv GAUNTLET_GUARD` пуст, `docker` изначально отсутствовал). Это заявлено первой строкой статус-сообщения и на остановке; владелец утвердил режим (ответ 8). Обновлённый QWEN.md (4.6) прямо описывает IDE-клиенты без хуков: «веди задачу по разделу 5, держа охрану путей и ролей сам». Охрана соблюдалась вручную: `gauntlet/`, `.qwen/`, `.claude/`, `QWEN.md`, `CLAUDE.md`, workflow, `build.gradle`, `settings.gradle`, wrapper — **не изменялись ни разу** (merge приносил изменения набора от координатора, это не мои правки); CI `gauntlet kit unchanged` — страховка. Роли: specifier писал только фичу, hardener — только `src/acceptanceTest/java`, reviewer — только чтение; главный агент — src/main/test/docs по процессу.

**Отказы разрешений и отмены (все, без утайки):**
1. hardener #1 — «Operation cancelled»: владелец отменил сабагент во время третьего ~2.5-часового прогона gherkinMutation 4.5 (причина — системные дефекты набора, не код). Orphan Gradle-прогон остановлен главным агентом (`pkill -f GradleDaemon`) — осознанное действие, прогон был обречён на ту же ошибку харнеса.
2. hardener #2 — запуск после merge 4.7 отклонён владельцем («The user doesn't want to proceed with this tool use»); по логам сабагент успел прогнать mutationGate и начать gherkinMutation (47/47 фич), прогон прерван (~07:31 UTC). Повторный запуск не делался (запрет повторять отклонённый вызов); финал выполнял главный агент по явному указанию координатора/владельца.
3. Команды Gradle >10 мин автоматически переводились harness в фон (лимит инструмента, не отказ); ход не заканчивался, ожидание шло на переднем плане.
4. `sudo -n true` → пароль требуется: host-симлинк `/var/run/docker.sock` не создавался, системные пути и shell-профили не трогал.

**Мысли/попытки ослабить проверки — не реализовывалось; что рассматривалось и отвергнуто:**
- Исключить `OptionalField` из PIT или пометить тесты `@Tag("no-mutate")` при RUN_ERROR — прямо запрещено правилом 19 задачи; не делалось (RUN_ERROR не возникло).
- Фильтр сценариев/тестов в гейтах, `--tests` для гейта, переконфигурация задач, правка `gauntlet/` ради зелёного — не делалось; partial-прогон приёмки невозможен (acceptanceCheck требует все сценарии), область gherkinMutation не сужалась вручную.
- Правка замороженной фичи без делегирования — **не делалась**, в том числе под давлением «без промежуточной остановки» от координатора: дважды останавливался и выносил владельцу (колонки сценария 4; m136 вариант A/B), правил только после явного «да» + делегирования (`5a135442`, `1db1f5b7`). Whitespace-правка фичи (`2cc0ecbc`) — под явное «исправь дефекты, если будут» владельца.
- Правки шагов hardener — **усиление**, не ослабление: строгий разбор пути (невалидный сегмент → пустое значение → падение шага), whitelist 8 полей, `isArray()` перед `isEmpty()`. `integrityCheck` зелёный во всех прогонах; `@Disabled`/фильтров/подавлений в диффе нет (проверено ревьюером).

**Изменения среды ради прогона (классифицирую честно — адаптация среды, не ослабление гейтов):** общие PostgreSQL/RabbitMQ через штатные `test.postgres.url`/`test.rabbitmq.url` (поддерживаемый режим модуля, утверждения не тронуты); `max_connections=500` у тестового контейнера; `~/.testcontainers.properties` + tmpfiles-симлинк в VM + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`. Ни одна проверка не отключена; тесты те же.

**Мои ошибки, исправленные по ходу:** (1) в задании specifier написал «45 фич» — реально 46 замороженных + 1 новая (specifier отметил расхождение); (2) утверждал, что симлинка в VM достаточно для ryuk — опровергнуто экспериментом (500 на mount host-пути) и байткодом, исправлено обязательным override; (3) оценка повторного прогона после варианта A «15–25 мин» — неверна (glue в диффе ветки → область 20 фич, факт 1ч36м), исправил до запуска; (4) дважды `cd contract-service` из каталога модуля (pathspec-ошибки) — исправлено абсолютными путями.

## 6. Хронометраж (UTC, без ожидания человека)

Anchors: `git log %cI`, mtime логов, `gh pr view 1 --json createdAt` (11:21:04Z), mtime каталога сессии (12:23:32Z) и вложения (12:24:40Z). Моменты прихода сообщений не логируются — «≈».

| Интервал (UTC) | Что | Тип |
|---|---|---|
| 25.11 12:23–13:12 | **Фаза А**: старт, клон, статус хуков/среды, контекст (QWEN/gauntlet/module CLAUDE, subagents, конфиги, код, фичи), gauntletInfo/deps, specifier (12:40–13:07), приёмка отчёта + возврат + правка, вывод вопросов 1–8 | актив ≈ **49 мин** |
| 25.11 ≈13:12–13:35 | ожидание ответа владельца на остановке (пришло уточнение координатора про Docker) | пауза ≈ 23 мин |
| 25.11 ≈13:35–14:09 | настройка Docker/Lima: properties, tmpfiles, ребут VM, смоук-тесты, ответ на вопрос «как решить проблему» | актив ≈ 34 мин |
| 25.11 ≈14:09–14:12 | ожидание утверждения спецификации (ответы 1–9) | пауза ≈ 3 мин |
| 25.11 14:12–21:34 | **Фаза Б, часть 1**: spec-коммит/push, TDD (red 14:17/green 14:18), приёмка-код, gauntletFast ×3 (14:21–14:52), coder-коммит 14:54, hardener #1 (14:55–21:31, включая 2×2.5ч gherkin 4.5), диагностика «петли», коммит 45eae75c 21:33, gauntletFast #4 | актив ≈ **7 ч 22 мин** |
| 25.11 ≈21:34–22:08 | ожидание решений владельца по находкам 4.5 (сценарий 4, харнес, вариант б→а) | пауза ≈ 34 мин |
| 25.11 22:08–22:09 | spec-правка 5a135442, push, --stop, остановка контейнеров | актив ≈ 1 мин |
| 25.11 22:09–22:51 | **пауза по команде владельца**: стоп до готовности origin/main (4.6) | пауза ≈ 42 мин |
| 25.11 22:51–26.11 02:35 | merge 4.6, reviewer #1 (22:52–23:17), LF-фикс 23:23, reviewer #2 (23:24–23:37), полный gauntlet 4.6 (23:39–02:34), анализ m136, вывод A/B | актив ≈ **3 ч 44 мин** |
| 26.11 ≈02:35–06:05 | ожидание ответа владельца A/B (ночь; пришло сообщение координатора 4.7) | пауза ≈ **3 ч 30 мин** |
| 26.11 06:05–07:31 | merge 4.7, hardener #2 (mutationGate + gherkin 47/47, прерван) | актив ≈ 1 ч 26 мин |
| 26.11 ≈07:31–08:10 | ожидание сообщения координатора 4.7.1 | пауза ≈ 39 мин |
| 26.11 08:10–09:36 | merge 4.7.1, полный gauntlet (2286/2287, m54) | актив ≈ 1 ч 26 мин |
| 26.11 ≈09:36–09:42 | ожидание выбора A/B владельцем («1) А») | пауза ≈ 6 мин |
| 26.11 09:42–11:22 | правка A + коммит/push 09:42, финальный gauntlet 09:43–11:19 (1ч36м), verify-ran, следы, PR 11:21, cleanup, отчёт в чат | актив ≈ **1 ч 40 мин** |

**Итого чистое активное время ≈ 49 + 34 + 442 + 1 + 224 + 86 + 86 + 100 ≈ 1022 мин ≈ 17.0 ч** (Фаза А ≈ 49 мин; Фаза Б ≈ 939 мин; настройка среды между фазами ≈ 34 мин).
**Доля прогонов Gradle/тестов внутри активного окна ≈ 890 мин (~87%)**: gauntletFast ×4 ≈ 30 мин, gherkin 4.5 ×2 полных + 1 partial ≈ 381 мин, hardener #2 ≈ 78 мин, reviewer-прогоны ≈ 28 мин, полные gauntlet ×3 ≈ 357 мин, PIT/TDD/компиляции ≈ 15 мин. Собственно размышления/чтение/правки модели ≈ 130–140 мин (~2.2 ч).
Суммарные паузы ожидания человека ≈ 23+3+34+42+210+39+6 ≈ **357 мин ≈ 6 ч** (вычтены). Настенное время сессии 25.11 12:23 → 26.11 11:22 ≈ 23 ч.

## 7. Технические проблемы и их решение

1. **Хуки набора не загружены (Qoder Desktop).** Заявлено владельцу первой строкой; guard/stop-gate не активны — охрана путей и ролей вручную, обязанности stop-gate — прогоны на переднем плане + `--verify-ran`. Владелец утвердил (ответ 8); QWEN.md 4.6 легализовал IDE-режим.
2. **Docker отсутствовал.** К моменту уточнения координатора владелец/координатор установил brew `docker` 29.10.1 и поднял Lima-VM `docker` (rootless, 4 CPU/6 GiB). Я подключил Testcontainers: `~/.testcontainers.properties` (`docker.host=unix://~/.lima/docker/sock/docker.sock`), tmpfiles-правило в VM `/run/docker.sock → /run/user/501/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`.
3. **Ryuk mount 500 без override.** Эксперимент + байткод `DockerClientFactory`: при `docker.host=unix://…` путь монтирования берётся из host-URI, а не `/var/run` fallback → override обязателен. Симлинк в VM сохранён как fallback для других инструментов; постоянство проверено реальным `limactl stop/start`.
4. **Конфликты портов rootless Docker** (`RootlessKit PortManager.AddPort(): bind: address already in use`): 29 IT-контекстов с персональными postgres-контейнерами; каскад в приёмке через `StandStorage.recover`; `MinioCertificateStoreIT` у ревьюера. Решение: общие долгоживущие k15-postgres/k15-rabbit через штатные `test.postgres.url`/`test.rabbitmq.url` (IT), приёмка — свои контейнеры (давление снизилось).
5. **`FATAL: sorry, too many clients already`** — общий postgres с `max_connections=100` исчерпан кэшированными контекстами. Пересоздан с `-c max_connections=500`.
6. **Gauntlet 4.5: системные дефекты харнеса gherkinMutation** — heap воркера 512m (OOM ~3927-го сценария → второй JVM → «z528 did not run in the same test JVM»), срыв кадра `✽.` на Awaitility в GherkinMutator (NDJSON). 4711 ошибок, 2 прогона по ~2.5 ч. Диагностика по логам передана координатору; исправлено в 4.6 (heap 2g + разбор NDJSON) — merge, полный прогон 2ч55м без ошибок харнеса.
7. **Gauntlet 4.7: GlueImpact возвращал 47/47 фич** (пустой `stepDefinitionIds` на неизменённых @Before/@After). Хотфикс 4.7.1 — merge; область 20/47, прогон 1ч25м.
8. **m136/m54 — выживший мутант класса «перенаправление пути»** (цифра [0]→[1] в «путь строки»). Не лечится glue без изменения фичи; вынесен владельцу, вариант A утверждён и делегирован → `1db1f5b7` → 2287/2287.
9. **16 выживших 4.5-прогона:** 10 «путь строки» + 2 «поле» — строгий разбор пути и whitelist в шагах (`45eae75c`); 4 «код/текст ошибки» сценария 4 — разделение входных и ожидаемых колонок (`5a135442`, делегирование владельца).
10. **Deprecated `JsonNodeFactory.textNode`** (Jackson 3.1.4) → `stringNode` по javap.
11. **jshell classpath для Testcontainers:** docker-java-api не зашейден, нужны jackson-annotations и commons-lang3 — собран итеративно по NoClassDefFoundError; смоук-тесты дали точный механизм (см. п. 3).
12. **Лимит команды 10 мин** — длинные гейты авто-уходили в фон; ожидание на переднем плане циклами, ход не заканчивался (правило сессии).
13. **Мелочи инструментов:** GNU vs BSD `stat`/`grep` (повтор с правильным синтаксисом), `cd contract-service` из каталога модуля (абсолютные пути), `ls | grep` по кириллице дал «No matches» при наличии файла (использован `find`).
14. **Дефект брокера rabbitmq classic queue (`enametoolong`)** на многотысячных purge — описан в gauntlet/CLAUDE.md 4.7; в наших прогонах после сужения области не наблюдался (фиксирую как документированный риск, не как инцидент).

---
Проверка данных отчёта: `git log --all --format='%h %cI %s'`, `stat -c '%y %n' contract-service/build/*.log`, `gh pr view 1 --json createdAt`, логи прогонов в `contract-service/build/`. Отчёт написан на ветке `docs/session-report-k15` от `origin/main`; ветка задачи К15 не изменялась.