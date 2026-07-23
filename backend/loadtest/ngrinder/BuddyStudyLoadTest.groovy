import static net.grinder.script.Grinder.grinder
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.is

import HTTPClient.NVPair
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom
import net.grinder.plugin.http.HTTPPluginControl
import net.grinder.plugin.http.HTTPRequest
import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import net.grinder.util.GrinderUtils
import org.junit.Test


class BuddyStudyLoadTest {
    static GTest test
    static HTTPRequest request
    static Map configuration

    @BeforeProcess
    static void beforeProcess() {
        String encoded = "__BUDDYSTUDY_CONFIG_BASE64__"
        if (encoded.startsWith("__BUDDYSTUDY_")) {
            encoded = GrinderUtils.getParam()
        }
        assert encoded
        String json = new String(Base64.decoder.decode(encoded), StandardCharsets.UTF_8)
        configuration = (Map) new JsonSlurper().parseText(json)
        HTTPPluginControl.connectionDefaults.timeout = (int) configuration.timeoutMs
        test = new GTest(1, "BuddyStudy ${configuration.scenario}")
        request = new HTTPRequest()
        test.record(request)
        grinder.logger.info(
            "BuddyStudy load test scenario={} requests={} validation={}",
            configuration.scenario,
            configuration.requests.size(),
            configuration.validateBody,
        )
    }

    @BeforeThread
    void beforeThread() {
        grinder.statistics.delayReports = true
    }

    @Test
    void executeRequest() {
        Map definition = chooseRequest()
        List<NVPair> headers = []
        if (definition.authenticated) {
            headers.add(new NVPair("Authorization", "Bearer ${configuration.accessToken}"))
        }
        headers.add(new NVPair("Accept", "application/json"))
        def response = request.GET(
            "${configuration.baseUrl}${definition.path}",
            null,
            headers as NVPair[],
        )

        assertThat(response.statusCode, is((int) definition.expectedStatus))
        if (configuration.validateBody) {
            validateResponse(definition, response.text)
        }
        grinder.statistics.forLastTest.success = 1
    }

    private static Map chooseRequest() {
        if (configuration.requests.size() == 1) {
            return (Map) configuration.requests[0]
        }
        int selected = ThreadLocalRandom.current().nextInt(100)
        int cumulative = 0
        for (Map definition : (List<Map>) configuration.requests) {
            cumulative += (int) definition.weight
            if (selected < cumulative) {
                return definition
            }
        }
        return (Map) configuration.requests[-1]
    }

    private static void validateResponse(Map definition, String text) {
        def body = new JsonSlurper().parseText(text)
        for (String path : (List<String>) definition.requiredJsonPaths) {
            assert readPath(body, path) != null
        }
        for (String path : (List<String>) definition.nonEmptyJsonPaths) {
            def value = readPath(body, path)
            if (value instanceof Collection) {
                assert !value.isEmpty()
            } else {
                assert Boolean.valueOf(value as String)
            }
        }
    }

    private static Object readPath(Object value, String path) {
        Object current = value
        for (String key : path.split("\\.")) {
            if (!(current instanceof Map) || !((Map) current).containsKey(key)) {
                return null
            }
            current = ((Map) current)[key]
        }
        return current
    }
}
