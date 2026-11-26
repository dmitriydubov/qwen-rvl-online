# Установка gauntlet в этот сервис

Инструкция для Claude Code: выполняется в первой сессии по запросу «выполни gauntlet/INSTALL.md».
Цель сессии: после неё `./gradlew gauntlet` зелёный на **текущем** коде, хуки и роли подключены,
PR открыт. Бизнес-код и существующие тесты не менять: всё, что найдено про старый код, идёт в отчёт.

Порядок важен: хуки становятся активны в момент появления `.claude/settings.json` (шаг 7) — после
этого `gauntlet/` для агентов доступен только на чтение. Поэтому вся настройка делается до шага 7.

## 0. Какие сборки охраняет gauntlet
Если в корне репозитория лежит `build.gradle(.kts)` и `gradlew` — это и есть сборка, `module` остаётся
пустым. Если репозиторий — монорепо с несколькими независимыми сборками в подкаталогах (свой
`settings.gradle`, `build.gradle`, `gradlew` у каждой), gauntlet подключается к тем из них, что названы
в запросе пользователя («… для модулей core и adapter»); если не названы — остановись и
спроси, предложив кандидатов (сервисы с логикой, а не библиотеки и тестовая обвязка).
- Впиши их в `gauntlet/config/gauntlet.properties`: `module=core,adapter`. Хуки будут
  запускать Gradle в каждом изменённом модуле, CI — во всех; `src/` и `build/` — каждого модуля,
  `gauntlet/` остаётся в корне. Для сборки в корне `module` остаётся пустым: шаблон поставляется пустым,
  `core,adapter` — только пример в его комментарии. `install.sh` до любых изменений отказывается, если запись
  `module` не называет существующий каталог, и перечисляет все такие записи.
- Общие пороги — в `gauntlet/config/`; то, что у модуля своё (`basePackage`, `acceptance.glue`,
  `exclude.classes`, архитектура), — в `gauntlet/config/<module>/`: `gauntlet.properties` там дополняет
  общий файл, `architecture.properties` (и при нужде `checkstyle.xml`, allowlist'ы) заменяет общий.
  Проверить, что подхватилось: `cd <module> && ./gradlew gauntletInfo`.
- Корневую сборку **не создавай**, остальные модули не трогай. Если у модуля нет своего `gradlew`,
  скопируй `gradlew`, `gradlew.bat` и `gradle/wrapper/` из соседнего модуля.
- Если модуль зависит от библиотеки-соседа (например `shared-lib`) через артефакт из
  внутреннего Maven-репозитория, облачная сборка его не достанет: подключи соседа как
  `includeBuild('../shared-lib')` в `settings.gradle` модуля (composite build, подмена
  по group:name автоматическая) — или, если это невозможно, остановись и опиши проблему.
Шаги 1–6 выполняются для каждого модуля; пути вида `src/…`, `build.gradle` и команды `./gradlew`
относятся к каталогу модуля.

## 1. Разведка
Определи и запиши для отчёта с доказательством (файл:строка или вывод команды):
- Java приложения (`sourceCompatibility`/`targetCompatibility`/toolchain), Gradle (`gradle/wrapper/gradle-wrapper.properties`
  и `./gradlew --version`), DSL сборки (Groovy или Kotlin);
- Spring Boot и остальные фреймворки (`build.gradle(.kts)`, `gradle/libs.versions.toml`, lock-файлы и
  `./gradlew dependencies`), JUnit и другие тестовые библиотеки; не угадывай версии по эталону;
- корневой пакет сервиса (`@SpringBootApplication`) и его прямые подпакеты;
- какие подпакеты от каких зависят сейчас (импорты между ними);
- основную ветку репозитория (`git remote show origin` → HEAD branch);
- как существующие интеграционные тесты поднимают контекст (Testcontainers? какие контейнеры?);
- есть ли уже `CLAUDE.md`, `.claude/`, `.github/workflows/`.

## 2. `gauntlet/config/gauntlet.properties` (при нескольких модулях — `gauntlet/config/<module>/gauntlet.properties`)
- `module`, `baseRef`, `cucumber.version` и пороги — только в общем файле.
- `basePackage` — корневой пакет модуля.
- `acceptance.glue` — `<basePackage>.acceptance`.
- `baseRef` — `origin/<основная ветка>`, если это не `main`.
- `project.java.version`, `project.gradle.version`, `project.framework.<имя>.version`,
  `project.test.<имя>.version` — фактические версии из шага 1, не значения шаблона. CI и роли используют
  эти записи; `./gradlew gauntletInfo` показывает их вместе с версиями инструментов.
- `gate.java.version` — major-версия JVM инструментов Gauntlet, не ниже 17: на ней работают инструменты
  набора и его ArchUnit-тест. Полную версию (`21.0.2`) Gradle читает как её major. Локальный путь задаётся только вне
  репозитория: `GAUNTLET_GATE_JAVA_HOME` или `-Pgauntlet.gateJavaHome` (JDK, не JRE); старый tracked `gate.java.home` не
  используется. Не меняй Java приложения или его toolchain. CI ставит все нужные JDK одним шагом `setup-java`;
  wrapper запускается с проектной `JAVA_HOME`, а gate JDK передаётся инструментам через
  `GAUNTLET_GATE_JAVA_HOME`. `setup-java` держит один JDK на major-версию: пиши каждую версию одинаково во всех
  сборках (`21` и `21.0.2` вместе CI отклонит), `1.8` читается как `8`. Если gate-версия пуста, CI использует `project.java.version`. В монорепо
  обе версии можно задать модулю в его оверлее: CI берёт их для каждой сборки отдельно. Минимум 17 проверяется
  и у `GAUNTLET_GATE_JAVA_HOME`: при пустой gate-версии и проекте ниже 17 CI остановится на `gauntletInfo` —
  впиши `gate.java.version` 17 или новее. Gate-версия должна быть и не ниже Java, под которую компилируется
  проект: `archTest`, приёмка и PIT загружают его классы на JVM гейта.
- `cucumber.version`, `archunit.version`, `pit.version`, `pit.junit5PluginVersion`, `jacoco.version`,
  `checkstyle.version`, `pmd.version` и плагин PIT — совместимые с обнаруженными Java/Gradle/JUnit/Spring
  версии, зафиксированные после реального прогона. Например, Boot 3.5 с JUnit 5.12 → Cucumber 7.23.0;
  Boot 4 с JUnit 6 → Cucumber 8.0.1. Это примеры, не разрешение менять зависимости проекта.
- Не обновляй JUnit, Spring Boot, Java, Gradle или другие зависимости приложения для совместимости гейтов.
  Если подходящей связки нет, остановись и вынеси владельцу варианты отдельным решением и отдельной задачей.
- `exclude.classes` — добавь маски тонких IO-адаптеров, которых достигают только интеграционные тесты
  (`*Repository`, `*Client`, `*Listener`, `*Publisher`…): их не мутируем и не считаем в CRAP.
  Не добавляй туда классы с бизнес-логикой.
- `spec.approval` оставь `chat`.
- `subagents.route` оставь `ask`: кто исполняет карточки сабагентов только для чтения — свои сабагенты или CLI
  другого вендора, которому уйдёт код, — решает владелец, ответом на вопрос в задаче или правкой этого ключа
  (`gauntlet/subagents.md`, раздел 5). Агент сам внешний маршрут не включает.

## 3. `gauntlet/config/architecture.properties` (при нескольких модулях — `gauntlet/config/<module>/architecture.properties`, общий файл тогда не используется)
- `base.package` — корневой пакет модуля.
- `allowed.<подпакет>=…` для **каждого** прямого подпакета — так, как зависимости устроены сейчас
  (гейт должен быть зелёным на текущем коде; незаявленный подпакет — падение с перечислением).
- `forbidden.*` оставь только те правила, которые выполняются уже сейчас; остальные закомментируй.
- В отчёт: рёбра, которые идут против направления «адаптеры → application → domain», и `forbidden`,
  которые пришлось снять. Ужесточать — решение человека, отдельными задачами.

## 4. Сборка
Kotlin DSL (`build.gradle.kts`):
```kotlin
plugins {
    // ...существующие
    id("info.solidsoft.pitest") version "1.19.0"
}
// ...
apply(from = "gauntlet/gauntlet.gradle")      // последней строкой; из модуля: "../gauntlet/gauntlet.gradle"
```
Groovy DSL (`build.gradle`):
```groovy
plugins {
    // ...существующие
    id 'info.solidsoft.pitest' version '1.19.0'
}
// ...
apply from: '../gauntlet/gauntlet.gradle'      // последней строкой; из корня: 'gauntlet/gauntlet.gradle'
```
Скрипт находит `gauntlet/` рядом с собой, а `src/` и `build/` берёт у сборки, в которой применён.
Он не меняет `./gradlew build`; все проверки идут через задачи группы `gauntlet`.

Если исходники не в UTF-8, кодировку нужно назвать явно: `options.encoding` задач `JavaCompile`. Gauntlet читает
исходники в кодировке, которую называет сборка, а без неё — как UTF-8. Сборка, которая полагается на кодировку
платформы (Windows с JDK 17 или более ранней), локально компилируется. Но гейт на ней падает с находкой E1, и так
же упадёт CI на Linux.

Если скрипт сборки задаёт историю PIT (`historyInputLocation`, `historyOutputLocation`,
`enableDefaultIncrementalAnalysis` или `withHistory` в `pitest { }` или у задачи `pitest`), убери её: с историей
PIT оставляет тесту прежние вердикты. Гейт отклоняет такую сборку, как только впервые запускает PIT; на первом
прогоне (шаг 6) мутировать нечего, и PIT не запускается.

## 5. Приёмочные тесты
- Скопируй `gauntlet/templates/acceptanceTest/java/.../CucumberSpringConfiguration.java` в
  `src/acceptanceTest/java/<пакет из acceptance.glue>/` и поправь `package`.
- Step-классы (glue) живут только в `src/acceptanceTest/java`. Правку glue в другом месте (отдельный модуль, общий
  source set шагов) область `changed` не видит, и `gherkinMutation` проверит пустое множество.
- Если сборка настраивает все задачи `Test` в `doFirst` (параллель, `maxParallelForks`, `forkEvery`, провайдеры
  `-D`) и он зарегистрирован раньше строки `apply`, он не должен касаться `acceptanceTest` и `gherkinMutantsRun`:
  приёмка идёт по одному форку, мутанты — в одном JVM по одному сценарию, и набор проверяет это по потоку
  сообщений Cucumber.
- Гейты читают поток сообщений Cucumber (`cucumber.plugin` `message:`): набор добавляет его к плагинам проекта
  из `systemProperty 'cucumber.plugin'` задачи (туда же Gradle кладёт `-D` из `jvmArgs`) или из
  `junit-platform.properties`. Свои плагины Cucumber задавай там, а не провайдером аргументов: его `-D` набор
  перекрывает, и плагин пропадает. Cucumber 8 пишет поток только с
  Jackson: набор добавляет `tools.jackson.core:jackson-databind` без версии в свои зависимости приёмки по
  умолчанию (версию задаёт BOM или dependency-management проекта). Если сборка сама создаёт source set
  `acceptanceTest` или его зависимости раньше строки `apply`, добавь Jackson сам:
  `acceptanceTestRuntimeOnly 'tools.jackson.core:jackson-databind'` или Jackson 2 —
  `com.fasterxml.jackson.core:jackson-databind` вместе с `com.fasterxml.jackson.datatype:jackson-datatype-jdk8`.
- Если контексту нужны PostgreSQL, RabbitMQ и т. п. — добавь в этот класс Testcontainers так же, как в
  существующих интеграционных тестах (`@ServiceConnection` / `@DynamicPropertySource`). На полном прогоне
  `gherkinMutation` сценарии выполняются тысячами подряд в одном JVM, поэтому держи `@Before`/`@After` быстрыми:
  очищай только непустые таблицы (проверкой `EXISTS` перед `TRUNCATE`), восстановление отказов стенда (пауза контейнера,
  сброс блокировки лидера, повторная подписка слушателей брокера) запускай в `@After` только если в сценарии реально
  вызывался шаг инжекции отказа (снимая флаг отказа лишь после полной переподписки слушателей), а тестовые очереди
  RabbitMQ дренируй циклом `basicGet` вместо `channel.queuePurge` (тысячи `queue.purge` на классической очереди
  RabbitMQ 4.1 копят `/` в пути сегмента индекса до `{error,enametoolong}`).
- Пример фичи `templates/.../example_discount.feature` **не копируй**: это только образец формата.
  Каталог `src/acceptanceTest/resources/features/` создай пустым (файл `.gitkeep`).

## 6. Первый прогон
`./gradlew gauntlet --continue` (из каталога сборки). Изменённых main-классов в этой ветке нет, поэтому Checkstyle, CRAP,
DRY, PIT и Gherkin-мутация проверят пустое множество; реально прогоняются `integrityCheck`, `test`,
`archTest` и `testCensus`. Чини только конфигурацию gauntlet:
- `integrityCheck` нашёл в старых тестах `@Disabled`, `assume*`, `disabledWithoutDocker` и т. п., а в скрипте
  сборки — фильтр тестов (`test { exclude '**/*IT*' }`, `useJUnitPlatform { excludeTags … }`) →
  запиши каждое в `gauntlet/config/integrity-allowlist.txt` с причиной `pre-existing at install`
  и перечисли в отчёте. Сами тесты и скрипт сборки не трогай.
- `testCensus` нашёл `INTEGRITY T6 <путь>  compiled but not run by test` — тестовый класс, который задача
  скомпилировала, но не запустила. Типичные причины: давний фильтр в скрипте сборки (`test { exclude '**/*IT*' }`
  при задаче `integrationTest`, `useJUnitPlatform { excludeTags … }`) или тестовой платформы, TestNG
  `@Test(enabled = false)`, тесты JUnit 4 без движка vintage (они не запускались никогда; движок не добавляй —
  зависимости при установке не меняются), `@TestFactory` без динамических тестов. Запиши каждое в
  `gauntlet/config/integrity-allowlist.txt` (или `gauntlet/config/<module>/`) строкой
  `<glob пути исходника> T6 # pre-existing at install: <почему>`: один glob на фильтр, когда имена это позволяют
  (`src/test/java/**/*IT.java T6`), иначе строку на класс. Строку B2 для самого фильтра в скрипте оставь, как
  раньше. Перечисли в отчёте; тесты и скрипт сборки не трогай. Так же `acceptanceCheck` называет фичу, сценарии
  которой отфильтрованы (`ACCEPTANCE F2 <путь>  X of Y scenario(s) ran`): строка `<glob пути фичи> F2 # …`.
- `archTest` перечислил незаявленные подпакеты или рёбра → шаг 3.
- `test` красный → это не про gauntlet. Если причина в окружении (нужен Docker: в облачной VM
  `service docker start` падает на ulimit — запусти `nohup dockerd --host=unix:///var/run/docker.sock &` и
  дождись `docker info`) — подними окружение; если тест падает по существу — в отчёт, не чини.
- `gauntletScope` не находит merge-base → `git fetch origin +refs/heads/<ветка>:refs/remotes/origin/<ветка>`
  (именно с refspec: клон облачной сессии может быть single-branch, и обычный `git fetch origin main`
  не создаст `origin/main`); если клон shallow — сначала `git fetch --unshallow origin`.
Повторяй до зелёного. В отчёт выпиши фактически проверенную матрицу `project.*`, `gate.java.*` и версий
инструментов. Просмотри diff манифестов сборки: там только подключение Gauntlet, никаких обновлений версий
приложения или его зависимостей. Такое обновление прекращает установку и уходит владельцу отдельным решением.

## 7. Подключение хуков — последним
Сначала `python3 gauntlet/hooks/selftest.py` — самопроверка хуков на этой VM, ожидается `0 failed`.
Затем `bash gauntlet/install.sh`. Скрипт создаст `.claude/settings.json`, `.claude/agents/` (роли specifier,
hardener, reviewer и `checker` — она исполняет карточки сабагентов только для чтения),
`.github/workflows/gauntlet.yml`, `.github/workflows/gauntlet-kit.yml` и добавит `@gauntlet/CLAUDE.md` в
корневой `CLAUDE.md` (существующие роли/workflow не перезаписывает: при отличиях создаёт `*.gauntlet.new`;
просмотри новую штатную версию, слей её с пользовательскими правками и удали сгенерированную копию). Если
`settings.json` уже содержит хук набора дважды, остаётся первый; хуки пользователя не трогаются. До любых
изменений скрипт отказывается и называет причину:
- символическая ссылка на месте `.claude`, `.claude/settings.json` или `.claude/agents` (guard охраняет
  настоящий путь, и файл по ссылке агенты могли бы менять) — замени ссылку настоящим файлом или каталогом;
- `settings.json`, который не объект, каталог или запись в нём не того типа (`hooks`, событие, группа, хук,
  `command`, `permissions`, `allow`/`deny`) — сообщение называет запись, исправь её.
После этого шага `gauntlet/`, `.claude/`, `CLAUDE.md` и файлы других агентных инструментов (`AGENTS.md`, `.codex/`,
`.gemini/`, `.agents/`, `.grok/`, `.kimi-code/`, `.qwen/`, `.mcp.json` и др., `gauntlet/README.md`, раздел 5) не
редактируй: их охраняет guard.

## 8. Коммит, push, PR
`git add -A && git commit -m "chore: install the gauntlet"`, `git push -u origin HEAD`, затем
`gh pr create` с отчётом:
- модуль (если монорепо) и как подключены соседние библиотеки;
- обнаруженные версии проекта (`project.*`), отдельная JVM гейта (`gate.java.*`), совместимые версии
  инструментов и доказательство их реального прогона; заполненные `basePackage`/`glue`/`baseRef`;
- `exclude.classes` и почему;
- архитектура: заявленные компоненты, рёбра против направления, снятые `forbidden`;
- integrity-исключения с причинами;
- файлы других агентных инструментов, которые уже есть в проекте: после merge их меняет только человек, а PR,
  который их меняет, красный на `gauntlet kit unchanged`;
- что не удалось прогнать и почему.

Человек прочитает отчёт и смержит PR — это и есть утверждение установки. После merge он делает обязательными
проверки `gauntlet` и `gauntlet kit unchanged` с источником GitHub Actions (`gauntlet/README.md`, раздел 5).
PR установки проверки `gauntlet kit unchanged` не показывает: в основной ветке ещё нет её workflow. Каждый
следующий PR обновления набора на ней красный — так задумано: человек читает список файлов и мержит сам.
