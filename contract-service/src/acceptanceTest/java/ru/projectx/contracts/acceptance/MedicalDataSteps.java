package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.cucumber.java.ru.Тогда;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.test.web.server.LocalManagementPort;

public class MedicalDataSteps {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ServiceJournal journal;
    private final AttachmentCalls calls;
    private final URI metrics;

    public MedicalDataSteps(ServiceJournal journal, AttachmentCalls calls, @LocalManagementPort int managementPort) {
        this.journal = journal;
        this.calls = calls;
        this.metrics = URI.create("http://localhost:" + managementPort + "/actuator/prometheus");
    }

    @Тогда("имя файла {string} и содержимое справки по вложению {string} не попали ни в журнал Договоров, ни в метрики, ни в ответ")
    public void medicalDataStaysOut(String fileName, String attachment) throws IOException, InterruptedException {
        String content = new String(calls.sentContent(attachment), StandardCharsets.UTF_8);
        String exposition = exposition();
        for (String value : new String[] {fileName, content}) {
            assertThat(journal.mentioning(value)).extracting(ILoggingEvent::getFormattedMessage)
                    .as("записи журнала с «%s»", value).isEmpty();
            assertThat(exposition).as("выдача метрик").doesNotContain(value);
            assertThat(calls.last().body()).as("тело ответа").doesNotContain(value);
        }
    }

    @Тогда("имя файла {string} и содержимое согласия по вложению {string} не попали ни в журнал Договоров, ни в метрики, ни в ответ")
    public void consentDataStaysOut(String fileName, String attachment) throws IOException, InterruptedException {
        medicalDataStaysOut(fileName, attachment);
    }

    private String exposition() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(metrics).timeout(TIMEOUT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("ответ /actuator/prometheus").isEqualTo(200);
        return response.body();
    }
}
